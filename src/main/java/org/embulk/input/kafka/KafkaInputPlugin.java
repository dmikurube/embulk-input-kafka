package org.embulk.input.kafka;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.BytesDeserializer;
import org.apache.kafka.common.utils.Bytes;
import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;
import org.embulk.config.ConfigDiff;
import org.embulk.config.ConfigException;
import org.embulk.config.ConfigSource;
import org.embulk.config.Task;
import org.embulk.config.TaskReport;
import org.embulk.config.TaskSource;
import org.embulk.spi.BufferAllocator;
import org.embulk.spi.Exec;
import org.embulk.spi.InputPlugin;
import org.embulk.spi.PageBuilder;
import org.embulk.spi.PageOutput;
import org.embulk.spi.Schema;
import org.embulk.spi.SchemaConfig;
import org.embulk.spi.time.TimestampParser;
import org.embulk.spi.util.Timestamps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class KafkaInputPlugin
    implements InputPlugin
{
  static final String MOCK_SCHEMA_REGISTRY_SCOPE = "embulk-input-kafka";

  public enum RecordSerializeFormat
  {
    JSON,
    AVRO_WITH_SCHEMA_REGISTRY;

    @JsonValue
    public String toString()
    {
      return name().toLowerCase(Locale.ENGLISH);
    }

    @JsonCreator
    public static RecordSerializeFormat ofString(String name)
    {
      switch (name.toLowerCase(Locale.ENGLISH)) {
        case "json":
          return JSON;
        case "avro_with_schema_registry":
          return AVRO_WITH_SCHEMA_REGISTRY;
        default:
      }

      throw new ConfigException(String.format(
          "Unknown serialize format '%s'. Supported modes are json, avro_with_schema_registry",
          name));
    }
  }

  public enum SeekMode
  {
    EARLIEST {
      @Override
      public void seek(KafkaConsumer<?, ?> consumer,
          List<TopicPartition> topicPartitions, Optional<Long> timestamp)
      {
        consumer.seekToBeginning(topicPartitions);
      }
    },
    TIMESTAMP {
      @Override
      public void seek(KafkaConsumer<?, ?> consumer,
          List<TopicPartition> topicPartitions, Optional<Long> timestamp)
      {
        if (timestamp.isPresent()) {
          Map<TopicPartition, Long> topicPartitionWithTimestamp = topicPartitions.stream()
              .collect(Collectors
                  .toMap(topicPartition -> topicPartition,
                      topicPartition -> timestamp.get()));
          Map<TopicPartition, OffsetAndTimestamp> topicPartitionOffsetAndTimestamp = consumer
              .offsetsForTimes(topicPartitionWithTimestamp);
          topicPartitionOffsetAndTimestamp.forEach(((topicPartition, offsetAndTimestamp) -> {
            if (offsetAndTimestamp != null) {
              consumer.seek(topicPartition, offsetAndTimestamp.offset());
            }
          }));
        }
      }
    };

    @JsonValue
    public String toString()
    {
      return name().toLowerCase(Locale.ENGLISH);
    }

    @JsonCreator
    public static SeekMode ofString(String name)
    {
      switch (name.toLowerCase(Locale.ENGLISH)) {
        case "earliest":
          return EARLIEST;
        case "timestamp":
          return TIMESTAMP;
        default:
      }

      throw new ConfigException(String
          .format("Unknown seek mode '%s'. Supported modes are earliest, timestamp",
              name));
    }

    public abstract void seek(KafkaConsumer<?, ?> consumer, List<TopicPartition> topicPartitions,
        Optional<Long> timestamp);
  }

  public enum TerminationMode {
    OFFSET_AT_START {
      @Override
      public Map<TopicPartition, Long> getOffsetsForTermination(
          KafkaConsumer<?, ?> consumer,
          List<TopicPartition> topicPartitions)
      {
        return consumer.endOffsets(topicPartitions);
      }
    },
    ENDLESS {
      @Override
      public Map<TopicPartition, Long> getOffsetsForTermination(
          KafkaConsumer<?, ?> consumer,
          List<TopicPartition> topicPartitions)
      {
        return ImmutableMap.of();
      }
    };

    @JsonCreator
    public static TerminationMode ofString(String name)
    {
      switch (name.toLowerCase(Locale.ENGLISH)) {
        case "offset_at_start":
          return OFFSET_AT_START;
        case "endless":
          return ENDLESS;
        default:
      }

      throw new ConfigException(String
          .format("Unknown seek mode '%s'. Supported modes are offset_at_start, endless",
              name));
    }

    public abstract Map<TopicPartition, Long> getOffsetsForTermination(
        KafkaConsumer<?, ?> consumer,
        List<TopicPartition> topicPartitions);
  }

  public interface PluginTask
      extends Task, TimestampParser.Task
  {
    @Config("brokers")
    public List<String> getBrokers();

    @Config("topics")
    public List<String> getTopics();

    @Config("schema_registry_url")
    @ConfigDefault("null")
    public Optional<String> getSchemaRegistryUrl();

    @Config("serialize_format")
    public RecordSerializeFormat getRecordSerializeFormat();

    @Config("seek_mode")
    @ConfigDefault("\"earliest\"")
    public SeekMode getSeekMode();

    @Config("termination_mode")
    @ConfigDefault("\"offset_at_start\"")
    public TerminationMode getTerminationMode();

    @Config("timestamp_for_seeking")
    @ConfigDefault("null")
    public Optional<Long> getTimestampForSeeking();

    @Config("key_column_name")
    @ConfigDefault("\"_key\"")
    public String getKeyColumnName();

    @Config("partition_column_name")
    @ConfigDefault("\"_partition\"")
    public String getPartitionColumnName();

    @Config("fetch_max_wait_ms")
    @ConfigDefault("30000")
    public int getFetchMaxWaitMs();

    @Config("max_empty_pollings")
    @ConfigDefault("2")
    public int getMaxEmptyPollings();

    @Config("other_consumer_configs")
    @ConfigDefault("{}")
    public Map<String, String> getOtherConsumerConfigs();

    @Config("value_subject_name_strategy")
    @ConfigDefault("null")
    public java.util.Optional<String> getValueSubjectNameStrategy();

    @Config("columns")
    public SchemaConfig getColumns();

    @Config("assignments")
    @ConfigDefault("[]")
    public List<List<String>> getAssignments();

    public void setAssignments(List<List<String>> assignments);
  }

  private static Logger logger = LoggerFactory.getLogger(KafkaInputPlugin.class);

  @Override
  public ConfigDiff transaction(ConfigSource config,
      InputPlugin.Control control)
  {
    PluginTask task = config.loadConfig(PluginTask.class);

    Schema schema = task.getColumns().toSchema();

    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, task.getBrokers());
    KafkaConsumer<Bytes, Bytes> consumer = new KafkaConsumer<>(props, new BytesDeserializer(),
        new BytesDeserializer());
    int maxTaskCount = Runtime.getRuntime().availableProcessors() * 2;

    List<List<String>> assignments = buildAssignments(consumer, task.getTopics(), maxTaskCount);
    int taskCount = Math.min(assignments.size(), maxTaskCount);

    task.setAssignments(assignments);

    return resume(task.dump(), schema, taskCount, control);
  }

  private List<List<String>> buildAssignments(KafkaConsumer<?, ?> consumer, List<String> topics,
      int maxTaskCount)
  {
    List<List<String>> assignments = IntStream.range(0, maxTaskCount)
        .mapToObj(n -> new ArrayList<String>()).collect(Collectors.toList());
    int taskIndex = 0;
    for (String topic : topics) {
      for (PartitionInfo partitionInfo : consumer.partitionsFor(topic)) {
        List<String> list = assignments.get(taskIndex);
        if (list == null) {
          list = new ArrayList<>();
        }
        list.add(String.format("%s:%d", partitionInfo.topic(), partitionInfo.partition()));
        taskIndex += 1;
        taskIndex = taskIndex % maxTaskCount;
      }
    }

    return assignments;
  }

  private List<TopicPartition> buildTopicPartitions(List<List<String>> assignments, int taskIndex)
  {
    List<TopicPartition> topicPartitions = new CopyOnWriteArrayList<>();
    assignments.get(taskIndex).forEach(assignmentInfo -> {
      String[] assignmentInfoArray = assignmentInfo.split(":");
      TopicPartition topicPartition = new TopicPartition(assignmentInfoArray[0],
          Integer.parseInt(assignmentInfoArray[1]));
      topicPartitions.add(topicPartition);
    });

    return topicPartitions;
  }

  @Override
  public ConfigDiff resume(TaskSource taskSource,
      Schema schema, int taskCount,
      InputPlugin.Control control)
  {
    control.run(taskSource, schema, taskCount);
    return Exec.newConfigDiff();
  }

  @Override
  public void cleanup(TaskSource taskSource,
      Schema schema, int taskCount,
      List<TaskReport> successTaskReports)
  {
  }

  @Override
  public TaskReport run(TaskSource taskSource,
      Schema schema, int taskIndex,
      PageOutput output)
  {
    PluginTask task = taskSource.loadTask(PluginTask.class);

    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, task.getBrokers());
    task.getOtherConsumerConfigs().forEach(props::setProperty);
    List<TopicPartition> topicPartitions = buildTopicPartitions(task.getAssignments(), taskIndex);
    switch (task.getRecordSerializeFormat()) {
      case JSON:
        JsonInputProcess jsonInputProcess = new JsonInputProcess(task, schema, output, props,
            topicPartitions);
        jsonInputProcess.run();
        break;
      case AVRO_WITH_SCHEMA_REGISTRY:
        AvroInputProcess avroInputProcess = new AvroInputProcess(task, schema, output, props,
            topicPartitions);
        avroInputProcess.run();
        break;
      default:
        throw new ConfigException("Unknown record_serialization_format");
    }

    TaskReport taskReport = Exec.newTaskReport();
    return taskReport;
  }

  abstract static class AbstractInputProcess<V>
  {
    protected final PluginTask task;
    private final Schema schema;
    private final PageOutput output;
    protected final Properties props;
    private final List<TopicPartition> topicPartitions;
    protected final TimestampParser[] timestampParsers;

    protected AbstractInputProcess(PluginTask task, Schema schema,
        PageOutput output, Properties props,
        List<TopicPartition> topicPartitions)
    {
      this.task = task;
      this.schema = schema;
      this.output = output;
      this.props = props;
      this.topicPartitions = topicPartitions;
      this.timestampParsers = Timestamps.newTimestampColumnParsers(task, task.getColumns());
    }

    public abstract KafkaConsumer<Bytes, V> getConsumer();

    public abstract AbstractKafkaInputColumnVisitor<V> getColumnVisitor(PageBuilder pageBuilder);

    public void run()
    {
      try (KafkaConsumer<Bytes, V> consumer = getConsumer()) {
        Map<TopicPartition, Long> offsetsForTermination = task
            .getTerminationMode()
            .getOffsetsForTermination(consumer, topicPartitions);

        assignAndSeek(task, topicPartitions, offsetsForTermination, consumer);

        BufferAllocator allocator = Exec.getBufferAllocator();
        try (PageBuilder pageBuilder = new PageBuilder(allocator, schema, output)) {
          final AbstractKafkaInputColumnVisitor<V> columnVisitor = getColumnVisitor(pageBuilder);

          boolean reassign = false;
          int emptyPollingCount = 0;

          while (!topicPartitions.isEmpty()) {
            if (reassign) {
              consumer.assign(topicPartitions);
            }

            ConsumerRecords<Bytes, V> records = consumer
                .poll(Duration.ofMillis(task.getFetchMaxWaitMs()));

            if (records.isEmpty()) {
              emptyPollingCount += 1;
              logger.info("polling results are empty. remaining count is {}",
                  task.getMaxEmptyPollings() - emptyPollingCount);
              if (emptyPollingCount >= task.getMaxEmptyPollings()) {
                break;
              }
            }

            for (ConsumerRecord<Bytes, V> record : records) {
              if (record.value() != null) {
                columnVisitor.reset(record);
                schema.visitColumns(columnVisitor);
                pageBuilder.addRecord();
              }

              TopicPartition topicPartition = new TopicPartition(record.topic(),
                  record.partition());
              if (task.getTerminationMode() == TerminationMode.OFFSET_AT_START
                  && record.offset() >= offsetsForTermination.get(topicPartition) - 1) {
                reassign = true;
                topicPartitions.remove(topicPartition);
              }
            }
          }

          pageBuilder.finish();
        }
      }
    }

    private void assignAndSeek(PluginTask task,
        List<TopicPartition> topicPartitions, Map<TopicPartition, Long> offsetsForTermination,
        KafkaConsumer<?, ?> consumer)
    {
      consumer.assign(topicPartitions);

      task.getSeekMode().seek(consumer, topicPartitions, task.getTimestampForSeeking());

      for (TopicPartition topicPartition : topicPartitions) {
        long position = consumer.position(topicPartition);
        if (position >= offsetsForTermination.get(topicPartition)) {
          topicPartitions.remove(topicPartition);
        }
      }

      consumer.assign(topicPartitions);
    }
  }

  static class JsonInputProcess extends AbstractInputProcess<ObjectNode>
  {
    JsonInputProcess(PluginTask task, Schema schema,
        PageOutput output, Properties props,
        List<TopicPartition> topicPartitions)
    {
      super(task, schema, output, props, topicPartitions);
    }

    @Override
    public KafkaConsumer<Bytes, ObjectNode> getConsumer()
    {
      return new KafkaConsumer<>(props, new BytesDeserializer(), new KafkaJsonDeserializer());
    }

    @Override
    public AbstractKafkaInputColumnVisitor<ObjectNode> getColumnVisitor(PageBuilder pageBuilder)
    {
      return new JsonFormatColumnVisitor(task, pageBuilder, timestampParsers);
    }
  }

  static class AvroInputProcess extends AbstractInputProcess<Object>
  {
    protected AvroInputProcess(PluginTask task, Schema schema, PageOutput output,
        Properties props, List<TopicPartition> topicPartitions)
    {
      super(task, schema, output, props, topicPartitions);
    }

    private KafkaAvroDeserializer buildKafkaAvroDeserializer()
    {
      KafkaAvroDeserializer kafkaAvroDeserializer = new KafkaAvroDeserializer();

      String schemaRegistryUrl = task.getSchemaRegistryUrl().orElseThrow(
          () -> new ConfigException("avro_with_schema_registry format needs schema_registry_url"));

      ImmutableMap.Builder<String, String> builder = ImmutableMap.<String, String>builder()
          .put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);

      if (task.getValueSubjectNameStrategy().isPresent()) {
        builder.put(AbstractKafkaSchemaSerDeConfig.VALUE_SUBJECT_NAME_STRATEGY,
            task.getValueSubjectNameStrategy().get());
      }
      Map<String, String> avroDeserializerConfig = builder.build();
      kafkaAvroDeserializer.configure(avroDeserializerConfig, false);

      return kafkaAvroDeserializer;
    }

    @Override
    public KafkaConsumer<Bytes, Object> getConsumer()
    {
      return new KafkaConsumer<>(props, new BytesDeserializer(), buildKafkaAvroDeserializer());
    }

    @Override
    public AbstractKafkaInputColumnVisitor<Object> getColumnVisitor(PageBuilder pageBuilder)
    {
      return new AvroFormatColumnVisitor(task, pageBuilder, timestampParsers);
    }
  }

  @Override
  public ConfigDiff guess(ConfigSource config)
  {
    return Exec.newConfigDiff();
  }
}
