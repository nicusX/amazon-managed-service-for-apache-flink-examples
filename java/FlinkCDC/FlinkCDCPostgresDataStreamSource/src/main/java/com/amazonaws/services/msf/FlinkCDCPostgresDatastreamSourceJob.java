package com.amazonaws.services.msf;

import com.amazonaws.services.kinesisanalytics.runtime.KinesisAnalyticsRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.cdc.connectors.base.source.jdbc.JdbcIncrementalSource;
import org.apache.flink.cdc.connectors.postgres.source.PostgresSourceBuilder;
import org.apache.flink.cdc.debezium.DebeziumDeserializationSchema;
import org.apache.flink.cdc.debezium.JsonDebeziumDeserializationSchema;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.environment.LocalStreamEnvironment;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;

public class FlinkCDCPostgresDatastreamSourceJob {
    private static final Logger LOG = LoggerFactory.getLogger(FlinkCDCPostgresDatastreamSourceJob.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Name of the local JSON resource with the application properties in the same format as they are received from the Amazon Managed Service for Apache Flink runtime
    private static final String LOCAL_APPLICATION_PROPERTIES_RESOURCE = "flink-application-properties-dev.json";

    private static final int DEFAULT_CDC_DB_PORT = 5432;

    private static boolean isLocal(StreamExecutionEnvironment env) {
        return env instanceof LocalStreamEnvironment;
    }

    /**
     * Load application properties from Amazon Managed Service for Apache Flink runtime or from a local resource, when the environment is local
     */
    private static Map<String, Properties> loadApplicationProperties(StreamExecutionEnvironment env) throws IOException {
        if (isLocal(env)) {
            LOG.info("Loading application properties from '{}'", LOCAL_APPLICATION_PROPERTIES_RESOURCE);
            return KinesisAnalyticsRuntime.getApplicationProperties(
                    FlinkCDCPostgresDatastreamSourceJob.class.getClassLoader()
                            .getResource(LOCAL_APPLICATION_PROPERTIES_RESOURCE).getPath());
        } else {
            LOG.info("Loading application properties from Amazon Managed Service for Apache Flink");
            return KinesisAnalyticsRuntime.getApplicationProperties();
        }
    }

    public static void main(String[] args) throws Exception {
        // set up the streaming execution environment
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        final Map<String, Properties> applicationProperties = loadApplicationProperties(env);
        LOG.warn("Application properties: {}", applicationProperties);

        // Enable checkpoints and set parallelism when running locally
        // On Managed Flink, checkpoints and application parallelism are managed by the service and controlled by the application configuration
        if (isLocal(env)) {
            env.setParallelism(1); // PostgreSQL FlinkCDC source captures data with parallelism = 1
            env.enableCheckpointing(30000);
        }


        DebeziumDeserializationSchema<String> deserializer =
                new JsonDebeziumDeserializationSchema();

        // Create CDC source table
        Properties cdcSourceProperties = applicationProperties.get("CDCSource");

        JdbcIncrementalSource<String> postgresIncrementalSource = PostgresSourceBuilder.PostgresIncrementalSource.<String>builder()
                .hostname(cdcSourceProperties.getProperty("hostname"))
                .port(Integer.parseInt(cdcSourceProperties.getProperty("port", String.valueOf(DEFAULT_CDC_DB_PORT))))
                .username(cdcSourceProperties.getProperty("username"))
                .password(cdcSourceProperties.getProperty("password"))
                .database(cdcSourceProperties.getProperty("database.name"))
                .tableList(cdcSourceProperties.getProperty("table.name"))
                .slotName(cdcSourceProperties.getProperty("slot.name"))
                .decodingPluginName("pgoutput") // For PostgreSQL 10_
                .deserializer(deserializer)
                .build();

        DataStreamSource<String> cdcSource = env.fromSource(postgresIncrementalSource,
                WatermarkStrategy.noWatermarks(),
                "Postgres CDC Source",
                TypeInformation.of(String.class));

        // Add passthrough map operator that logs pretty-printed JSON
        DataStream<String> loggedCdcStream = cdcSource.map(record -> {
            try {
                // Parse and pretty-print the JSON
                JsonNode jsonNode = objectMapper.readTree(record);
                String prettyJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode);
                
                LOG.info("CDC Record:\n{}", prettyJson);
            } catch (Exception e) {
                // If JSON parsing fails, log the raw record
                LOG.warn("Failed to parse JSON for pretty printing the CDC record: {}", record);
            }
            
            // Return the original record unchanged (passthrough)
            return record;
        }).name("CDC record Logger");


        env.execute("Postgres CDC job");
    }

}
