# FlinkCDC MySQL source example

This example shows how to capture data from a database (MySQL in this case) directly from Flink using a 
[Flink CDC source connector](https://nightlies.apache.org/flink/flink-cdc-docs-release-3.4/docs/connectors/flink-sources/overview/).

* Flink version: 1.20
* Flink API: SQL
* Language: Java (11)
* Flink connectors: Flink CDC MySQL source (3.4), DynamoDB sink

The job is implemented in SQL embedded in Java.
It uses the [Flink CDC MySQL source connector](https://nightlies.apache.org/flink/flink-cdc-docs-release-3.4/docs/connectors/flink-sources/mysql-cdc/) to capture changes from 
a database, and sink data to a DynamoDB table doing upsert by primary key, so the destination table always contains the latest state of each record.

### How Flink CDC works

[Flink CDC](https://nightlies.apache.org/flink/flink-cdc-docs-release-3.4/) can be used in two separate way:
1. To deploy a **self-managed** e2e data pipeline
2. As a [Flink CDC source](https://nightlies.apache.org/flink/flink-cdc-docs-release-3.4/docs/connectors/flink-sources/overview/) 
   which acts as a source connector, and can be used in any Flink application. This is the pattern shown in this example.

Flink CDC behavior slightly differs depending on the captured database and whether the source is defined using SQL or DataStream API.
In this example we cover SQL.

When a CDC Source starts by default it takes a snapshot of the entire database, generating an Insert message for each record
currently. This behaviour is configurable. The MySQL CDC Source is able to parallelize the snapshot and leverage a parallelism 
greater than 1 in Flink. 

After the initial snapshot, the CDC Source captures the changes in the source database in real time.
Note that this process cannot be parallelized. If you set the source with a parallelism > 1, only one subtask will 
actually process changes.

The Flink CDC MySQL source relies on Flink checkpoints. If checkpoints are not enabled, the source will do the initial snapshot 
of the records already in the table, but it will not capture any further changes.

See [Flink CDC MySQL Source documentation](https://nightlies.apache.org/flink/flink-cdc-docs-release-3.4/docs/connectors/flink-sources/mysql-cdc/)
for further details.

###  Pre-requisite setup

To run this example on Amazon Managed Service for Apache Flink you require the following additional resources:
1. An **Aurora RDS MySQL cluster**
  * Same VPC and Security Group as the Managed Flink application
  * Credential management: *Managed in AWS Secrets Manager*
  * RDS Data API: enabled, to allow using the Query Editor in AWS Console for testing
2. A **DynamoDB table** named "Orders"
  * Partition key: `order_id` (Number) 

You can use the CloudFormation template [infrastructure.yaml](infrastructure.yaml) provided, to create the Aurora 
database and the DynamoDB table. 
On stack creation, select the same VPC and Security Group you will use for the Managed Flink application.

The CloudFormation template also populates the Aurora database with `sales` database, an `orders` table with some 
initial records, and a database user with limited permissions for CDC.

#### Pre-requisites when running locally

For local development, the Aurora RDS MySQL database can be replaced by a local version of MySQL in docker.
You can use the docker-compose file provided in the [docker](./docker) subfolder.

This docker-compose file creates and populates a MySQL database with the following configuration:

* Hostname: `localhost`
* Port: `3306`
* Root password: `rootpassword`
* CDC user 
  * username: `flinkusr`
  * password: `flinkpw`
* Database: `sales`

> **Note**: The docker-compose stack does not include a local version of DynamoDB. 
> Your application running in the IDE can reach the actual DynamoDB following the instructions to 
> [Run examples locally](../../running-examples-locally.md).

### Runtime configuration

The application reads the runtime configuration from the Runtime Properties, when running on Amazon Managed Service for Apache Flink,
or, when running locally, from the [`resources/flink-application-properties-dev.json`](resources/flink-application-properties-dev.json) file located in the resources folder.

All parameters are case-sensitive.

| Group ID       | Key             | Default     | Description                                                                                                                                                                                                                      | 
|----------------|-----------------|-------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `CDCSource`    | `hostname`      | (none)      | Hostname of the database endpoint.                                                                                                                                                                                               |
| `CDCSource`    | `port`          | 3306        | Port of the database endpoint                                                                                                                                                                                                    |
| `CDCSource`    | `username`      | (none)      | Db user for Flink CDC                                                                                                                                                                                                            |
| `CDCSource`    | `password`      | (none)      | Password of the db user. For simplicity, we pass the secret unencrypted, but you should not do this in production.                                                                                                               |
| `CDCSource`    | `database.name` | `sales`     | Name of the database                                                                                                                                                                                                             |
| `CDCSource`    | `table.name`    | `orders`    | Name of the table to capture                                                                                                                                                                                                     |
| `CDCSource`    | `server.id`     | `1001-1064` | ID of the CDC client. This must be a range of integers such as `1-64`. The range must include a number of elements greather than the source parallelism. These ID must be unique across all CDC clients on the same db instance. |
| `DynamoDBSink` | `table.name`    | `Orders`    | Name of the DynamoDB table to write into.                                                                                                                                                                                        |                                                                                                                                                                                                                              
| `DynamoDBSink` | `aws.region`    | (none)      | Region of the DynamoDB table.                                                                                                                                                                                                    |


### Running in IntelliJ for local development

You can run this example directly in IntelliJ, without any local Flink cluster or local Flink installation.
See [Running examples locally](../running-examples-locally.md) for details.

### Testing Change Data Capture

To see Flink CDC working:
1. Create the DynamoDB table, create and populate the Aurora database (or the local MySQL)
2. Start the application. When the application starts it executes a snapshot of the source database copying all existing records into DynamoDB table.
   You can observe them from the  [DynamoDB console](https://console.aws.amazon.com/dynamodbv2/home:
   select `Orders` table, click *Explore table items*, select *Scan* and *Run* the query.
3. Simulate changes in the source MySQL database executing some INSERT, UPDATE and DELETE statement, as explained in
   [Simulating changes in the source database](#simulating-changes-in-the-source-database), below.
4. Observe the changes being propagated in DynamoDB running again the *Scan* on the table

> Note: if you are running the example application multiple times, we recommend to delete all items in the destination 
> DynamoDB table to observe the behavior of the CDC connector.

#### Simulating changes in the source database

When running the example on Amazon Managed Service for Apache Flink, capturing changes from Aurora RDS MySQL, you can use
Query Editor in the [Aurora RDS console page](https://console.aws.amazon.com/rds) to insert/update records.

Execute the following 
```sql
USE sales;
INSERT INTO orders VALUES
    (3, '2025-06-20 12:15:00', 'Third customer', 37, 17.00, true),
    (4, '2025-06-20 13:00:27', 'Fourth customer', 51, 12.34, false);

UPDATE orders
    SET customer_name = '2nd customer', price = 99.98
    WHERE order_id = 2
```

When you are running MySQL in docker, you can connect to the mysql cli client with the following command, 
and execute the SQL statements above:

```
docker exec -it mysql_db mysql -u root -prootpassword -D sales
```

> To quit the mysql client type `\quit`

### (optional) Manually populating Aurora MySQL database

If you create the Aurora database and DynamoDB table manually, you need to populate them using the following SQL script 
you can execute from the Query Editor in the [Aurora RDS console page](https://console.aws.amazon.com/rds).

> This is not required if you created the resources using the [CloudFormation template](infrastructure.yaml) and is not 
> required when running MySQL locally, in Docker.

```sql
-- Create a db user for Flink CDC with limited permissions
CREATE USER 'flinkusr'@'%' IDENTIFIED BY 'flinkpw';
GRANT SELECT, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'flinkusr'@'%';
FLUSH PRIVILEGES;
      
-- Create database
CREATE DATABASE sales;
       
-- Create table
USE sales;
CREATE TABLE orders (
  order_id INT PRIMARY KEY,
  order_date TIMESTAMP(0),
  customer_name VARCHAR(255),
  product_id INT,
  price DECIMAL(10,5),
  order_status BOOLEAN
);

-- Insert initial records
INSERT INTO orders VALUES
   (1, '2025-06-16 11:01:00', 'My first customer', 42, 27.35, true),
   (2, '2025-06-16 11:03:00', 'Second customer', 43, 13.76, false);

-- Verify records have been inserted
SELECT * FROM orders;
```
