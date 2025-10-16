package com.amazonaws.services.msf;

import com.amazonaws.services.kinesisanalytics.runtime.KinesisAnalyticsRuntime;
import com.amazonaws.services.msf.domain.StockPrice;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SerializationSchema;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.connector.kinesis.sink.KinesisStreamsSink;
import org.apache.flink.formats.json.JsonDeserializationSchema;
import org.apache.flink.formats.json.JsonSerializationSchema;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.LocalStreamEnvironment;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

public class BoundedKafkaConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(BoundedKafkaConsumer.class);
    private static final String LOCAL_APPLICATION_PROPERTIES_RESOURCE = "flink-application-properties-dev.json";

    private static boolean isLocal(StreamExecutionEnvironment env) {
        return env instanceof LocalStreamEnvironment;
    }

    private static Map<String, Properties> loadApplicationProperties(StreamExecutionEnvironment env) throws IOException {
        if (isLocal(env)) {
            LOG.info("Loading application properties from '{}'", LOCAL_APPLICATION_PROPERTIES_RESOURCE);
            return KinesisAnalyticsRuntime.getApplicationProperties(
                    Objects.requireNonNull(BoundedKafkaConsumer.class.getClassLoader()
                            .getResource(LOCAL_APPLICATION_PROPERTIES_RESOURCE)).getPath());
        } else {
            LOG.info("Loading application properties from Amazon Managed Service for Apache Flink");
            return KinesisAnalyticsRuntime.getApplicationProperties();
        }
    }

    private static KafkaSource<StockPrice> createBoundedKafkaSource(Properties inputProperties) {

        return KafkaSource.<StockPrice>builder()
                .setBootstrapServers(inputProperties.getProperty("bootstrap.servers"))
                .setTopics(inputProperties.getProperty("topic"))
                .setGroupId(inputProperties.getProperty("group.id"))
                .setStartingOffsets(OffsetsInitializer.committedOffsets(OffsetResetStrategy.EARLIEST))
                .setBounded(OffsetsInitializer.latest())
                .setValueOnlyDeserializer(new JsonDeserializationSchema<>(StockPrice.class))
                .setProperties(inputProperties)
                .build();
    }

    private static KinesisStreamsSink<StockPrice> createKinesisSink(Properties outputProperties, SerializationSchema<StockPrice> serializationSchema) {
        return KinesisStreamsSink.<StockPrice>builder()
                .setStreamArn(outputProperties.getProperty("stream.arn"))
                .setKinesisClientProperties(outputProperties)
                .setSerializationSchema(serializationSchema)
                .setPartitionKeyGenerator(element -> element.getTicker())
                .build();
    }

    public static void main(String[] args) throws Exception {
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // Disable operator chaining just observe the traffic between the operators
        env.disableOperatorChaining();

//        if (isLocal(env)) {
//            env.enableCheckpointing(10_000);
//            env.setParallelism(2);
//        }

        final Map<String, Properties> applicationProperties = loadApplicationProperties(env);
        LOG.info("Application properties: {}", applicationProperties);

        Properties authProperties = applicationProperties.getOrDefault("AuthProperties", new Properties());
        Properties inputProperties = new Properties();
        inputProperties.putAll(applicationProperties.get("Input0"));
        inputProperties.putAll(authProperties);

        KafkaSource<StockPrice> source = createBoundedKafkaSource(inputProperties);
        DataStream<StockPrice> input = env.fromSource(source, WatermarkStrategy.noWatermarks(), "Kafka source");

        KinesisStreamsSink<StockPrice> sink = createKinesisSink(applicationProperties.get("Output0"), new JsonSerializationSchema<>());
        input.sinkTo(sink);

        input.print();

        env.execute("Bounded Kafka Consumer to Kinesis");
    }
}
