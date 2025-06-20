package com.amazonaws.services.msf;

import com.amazonaws.services.kinesisanalytics.runtime.KinesisAnalyticsRuntime;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.cdc.common.utils.Preconditions;
import org.apache.flink.streaming.api.environment.LocalStreamEnvironment;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.bridge.java.StreamStatementSet;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;

import static org.apache.commons.lang3.StringUtils.*;

public class FlinkCDCMySQLSourceJob {
    private static final Logger LOG = LoggerFactory.getLogger(FlinkCDCMySQLSourceJob.class);

    // Name of the local JSON resource with the application properties in the same format as they are received from the Amazon Managed Service for Apache Flink runtime
    private static final String LOCAL_APPLICATION_PROPERTIES_RESOURCE = "flink-application-properties-dev.json";

    private static final int DEFAULT_CDC_DB_PORT = 3306;
    private static final String DEFAULT_CDC_DATABASE_NAME = "sales";
    private static final String DEFAULT_CDC_TABLE_NAME = "orders";
    private static final String DEFAULT_CDC_SERVER_ID = "1001-1064";
    private static final String DEFAULT_SINK_DDB_TABLE_NAME = "Orders";

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
                    FlinkCDCMySQLSourceJob.class.getClassLoader()
                            .getResource(LOCAL_APPLICATION_PROPERTIES_RESOURCE).getPath());
        } else {
            LOG.info("Loading application properties from Amazon Managed Service for Apache Flink");
            return KinesisAnalyticsRuntime.getApplicationProperties();
        }
    }


    public static void main(String[] args) throws Exception {
        // set up the streaming execution environment
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        final StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env, EnvironmentSettings.newInstance().build());

        final Map<String, Properties> applicationProperties = loadApplicationProperties(env);
        LOG.warn("Application properties: {}", applicationProperties);

        // Enable checkpoints and set parallelism when running locally
        // On Managed Flink, checkpoints and application parallelism are managed by the service and controlled by the application configuration
        if (isLocal(env)) {
            env.enableCheckpointing(60000);
        }


        // Create CDC source table
        Properties cdcSourceProperties = applicationProperties.get("CDCSource");
        tableEnv.executeSql("CREATE TABLE Orders (" +
                "  order_id INT," +
                "  table_name STRING," +
                "  order_date TIMESTAMP(0)," +
                "  customer_name VARCHAR(255)," +
                "  product_id INT," +
                "  price DECIMAL(10,5)," +
                "  order_status BOOLEAN," +
                // Some additional metadata columns for demonstration purposes
                "  `_ingestion_ts` AS PROCTIME()," + // The time when Flink is processing this record
                "  `_operation_ts` TIMESTAMP_LTZ(3) METADATA FROM 'op_ts' VIRTUAL," + // The time when the operation was executed on the db
                "  `_last_operation` STRING METADATA FROM 'row_kind' VIRTUAL," + // Operation represented by this record (insert, before update, after update, delete)
                "  `_table_name` STRING METADATA FROM 'table_name' VIRTUAL," + // Name of the table in the source db
                "  `_db_name` STRING METADATA FROM 'database_name' VIRTUAL," + // name of the database
                "  PRIMARY KEY(order_id) NOT ENFORCED" +
                ") WITH (" +
                "  'connector' = 'mysql-cdc'," +
                "  'hostname' = '" + Preconditions.checkNotNull(cdcSourceProperties.getProperty("hostname"), "missing CDC source hostname") + "'," +
                "  'port' = '" + Preconditions.checkNotNull(cdcSourceProperties.getProperty("port", Integer.toString(DEFAULT_CDC_DB_PORT)), "missing CDC source port") + "'," +
                "  'username' = '" + Preconditions.checkNotNull(cdcSourceProperties.getProperty("username"), "missing CDC source username") + "'," +
                // For simplicity, we are passing the db password as a runtime configuration unencrypted. This should be avoided in production
                "  'password' = '" + Preconditions.checkNotNull(cdcSourceProperties.getProperty("password"), "missing CDC source password") + "'," +
                "  'database-name' = '" + Preconditions.checkNotNull(cdcSourceProperties.getProperty("database.name", DEFAULT_CDC_DATABASE_NAME), "missing CDC source database name") + "'," +
                "  'table-name' = '" + Preconditions.checkNotNull(cdcSourceProperties.getProperty("table.name", DEFAULT_CDC_TABLE_NAME), "missing CDC source table name") + "'," +
                "  'server-id' = '" + Preconditions.checkNotNull(cdcSourceProperties.getProperty("server.id", DEFAULT_CDC_SERVER_ID), "missing CDC source server id") + "'," +
                "  'server-time-zone' = 'UTC' " +
                ")");


        // Create a DynamoDB sink table
        Properties dynamoDBProperties = applicationProperties.get("DynamoDBSink");
        tableEnv.executeSql("CREATE TABLE DDBSinkTable (" +
                "  order_id INT," +
                "  table_name STRING," +
                "  order_date TIMESTAMP(0)," +
                "  customer_name VARCHAR(255)," +
                "  product_id INT," +
                "  price DECIMAL(10,5)," +
                "  order_status BOOLEAN," +
                "  `_ingestion_ts` TIMESTAMP_LTZ(3)," +
                "  `_operation_ts` TIMESTAMP_LTZ(3)," +
                "  `_last_operation` STRING," +
                "  `_table_name` STRING," +
                "  `_db_name` STRING" +
 //               "  PRIMARY KEY(order_id) NOT ENFORCED" +
                ") PARTITIONED BY (`order_id`) " +
                "WITH (" +
                "  'connector' = 'dynamodb'," +
                "  'table-name' = '" + Preconditions.checkNotNull(dynamoDBProperties.getProperty("table.name", DEFAULT_CDC_TABLE_NAME), "missing DynamoDB table name") + "'," +
                "  'aws.region' = '" + Preconditions.checkNotNull(dynamoDBProperties.getProperty("aws.region"), "missing AWS region") + "'" +
                ")");

        // While developing locally, you can comment the sink table to DynamoDB and uncomment the following table to print records to the console
        // When the job is running on Managed Flink any output to console is not visible
        tableEnv.executeSql("CREATE TABLE PrintSinkTable (" +
                "  order_id INT," +
                "  table_name STRING," +
                "  order_date TIMESTAMP(0)," +
                "  customer_name VARCHAR(255)," +
                "  product_id INT," +
                "  price DECIMAL(10,5)," +
                "  order_status BOOLEAN," +
                "  `_ingestion_ts` TIMESTAMP_LTZ(3)," +
                "  `_operation_ts` TIMESTAMP_LTZ(3)," +
                "  `_operation` STRING," +
                "  `_table_name` STRING," +
                "  `_db_name` STRING," +
                "  PRIMARY KEY(order_id) NOT ENFORCED" +
                ") WITH (" +
                "  'connector' = 'print'" +
                ")");


        StreamStatementSet statementSet = tableEnv.createStatementSet();
        statementSet.addInsertSql("INSERT INTO DDBSinkTable SELECT * FROM Orders");
        statementSet.addInsertSql("INSERT INTO PrintSinkTable SELECT * FROM Orders");

        // Execute the two INSERT INTO statements
        statementSet.execute();
    }
}
