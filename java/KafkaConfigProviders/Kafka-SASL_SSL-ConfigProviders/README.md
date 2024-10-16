## Example showing configuring Kafka connector for SASL/SCRAM using MSK Config Providers

* Flink version: 1.20
* Flink API: DataStream API
* Language: Java (11)
* Connectors: Kafka

This example illustrates how to set up a Kafka connector to MSK using SASL/SCRAM authentication, fetching the credentials
at runtime directly from SecretsManager.

For simplicity, the application generates random data internally, using the Data Generator Source, and demonstrates how to 
set up a KafkaSink with SASL/SCRAM authentication.
The configuration of a KafkaSource is identical to the sink, for what regards SASL/SCRAM authentication.


### High level approach

This application uses config providers to fetch secrets for setting up SASL/SCRAM authentication at runtime, when the application starts.
In particular, these two config providers are used:

1. **SecretsManager config provider**: to retrieve SASL username and password from SecretsManager
2. **S3 config provider** (optional, if your Kafka cluster uses self-signed certificates): to fetch the TrustStore from an S3 bucket. 
  **This step is not required with MSK** (see below).

This project includes the pre-packaged JAR with the MSK Config Providers, setting up a local repository in the [POM](./pom.xml).
You can find sources of the Config Providers in
[https://github.com/aws-samples/msk-config-providers](https://github.com/aws-samples/msk-config-providers).


#### Optional custom TrustStore

**This step is not required if you are using MSK**.

If your Kafka cluster uses a self-signed certificate, or a certificate signed with a private CA, you need to provide a 
custom TrustStore containing either the server certificate or the CA used for signing it.

This example shows how the Flink job can download a custom TrustStore from S3 when the application starts.

MSK uses server certificates signed with a public CA recognized by the JVM directly.
No custom TrustStore is needed in this case.


### Set up dependencies

To run this example you need the following dependencies:

1. Set up an MSK cluster with SASL/SCRAM authentication configured and a SecretsManager secret containing the SASL credentials of a valid user.
   (see [SASL credentials in SecretsManager](#sasl-credentials-in-secretsmanager), below).  
2. On the MSK cluster either enable topic creation (`auto.create.topics.enable=true`) or manually create the destination topic.
3. If required, upload a custom TrustStore (jks file) into an S3 bucket (**not required with MSK**).
4. Configure the Managed Service for Apache Flink application VPC networking, ensuring the application has network access to the MSK cluster.
   (see [Configure Managed Service for Apache Flink to access resources in an Amazon VPC](#https://docs.aws.amazon.com/managed-flink/latest/java/vpc.html) documentation for details).
5. Create a VPC Endpoint for SecretsManager in the VPC of the application.
   (see the blog post [How to connect to AWS Secrets Manager service within a Virtual Private Cloud](https://aws.amazon.com/blogs/security/how-to-connect-to-aws-secrets-manager-service-within-a-virtual-private-cloud/) for details).
6. Ensure the IAM Role of the Managed Service for Apache Flink application allows access the secret in SecretsManager, 
   and to the TrustStore in S3, if required (see [Application IAM permissions](#application-iam-permissions), below).

#### SASL credentials in SecretsManager

The application assumes the SecretsManager secret with SASL credentials is the same used by MSK. 
The secret contains two keys, username and password respectively.

See [MSK documentation](https://docs.aws.amazon.com/msk/latest/developerguide/msk-password.html) for more details about setting up 
SASL/SCRAM authentication and creating a user.

At runtime, the application uses the `secretsmanager` config provider twice, to fetch username and password, and to build the value for `sasl.jaas.config`
configuration in the format expected by the Kafka client.

```
org.apache.kafka.common.security.scram.ScramLoginModule required username="<username>" password="<password>";
```

Note: the double-quotes surrounding username and password and the closing semicolon.

Note: MSK requires SASL credentials to be encrypted with a custom AWS KMS key. The application must also have permissions to 
access the key to decrypt the secret (see [Application IAM permissions](#application-iam-permissions), below).

#### (Optional) Upload custom TrustStore for Kafka SSL in S3

**This step is not required if you are using MSK**.

If you need to provide a custom TrustStore with the server certificate, or the CA used to sign it, you can upload a custom 
TrustStore jks file to an S3 bucket and have the application downloading it on start, using the `s3import` Config Provider.

The code for doing this is commented out in this example. 
Uncomment the code and add the required [configuration parameters](#application-configuration-parameters) to the runtime configuration.

#### Application IAM permissions

In addition to any other IAM permission required by the application, to dynamically fetch SASL credentials the application
also requires the following permissions:

1. To read the secret from Secret Manager:
   * Actions: `secretsmanager:GetSecretValue`, `secretsmanager:DescribeSecret`, `secretsmanager:DescribeSecret`, `secretsmanager:GetResourcePolicy`, `secretsmanager:ListSecretVersionIds`
   * Resource: `arn:aws:secretsmanager:<region>:<account-id>:secret:<secret-name>`
2. To decrypt the secret stored in SecretsManager - This is the KMS key used to encrypt the secret with the SASL credentials
   * Action: `kms:Decrypt`
   * Resource: `arn:aws:kms:<region>:<account-id>:key/<key-id>`
3. To download the TrustStore file from S3 (only required if you need a custom TrustStore)
   * Actions: `s3:GetObject`
   * Resource: `arn:aws:s3:::<bucket-name>/<object-key>`

For example, and IAM Policy like the following:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "SecretFromSecretManager",
      "Effect": "Allow",
      "Action": [
        "secretsmanager:GetSecretValue",
        "secretsmanager:ListSecrets",
        "secretsmanager:DescribeSecret",
        "secretsmanager:GetResourcePolicy",
        "secretsmanager:ListSecretVersionIds"
      ],
      "Resource": "arn:aws:secretsmanager:<region>:<account>:secret:<secret-name>"
    },
    {
      "Sid": "DecryptSecrets",
      "Effect": "Allow",
      "Action": [
        "kms:Decrypt"
      ],
      "Resource": "arn:aws:kms:<region>:<account>:key/<key-id>"
    },
    {
      "Sid": "ReadTrustStoreFromS3",
      "Effect": "Allow",
      "Action": [
        "s3:GetObject"
      ],
      "Resource": "arn:aws:s3:::<bucket-name>/<path-to-truststore-file>"
    }
  ]
}
```


Note: application uses VPC networking to MSK. This requires additional permissions. 
See [VPC application permissions](https://docs.aws.amazon.com/managed-flink/latest/java/vpc-permissions.html)
in the service documentation, for details.

Note: no IAM permissions to MSK is required for using SASL/SCRAM. When using SASL/SCRAM authentication, access to MSK
is controlled via networking (SecurityGroups, NACL) and by the SASL credentials provided.


### Application configuration parameters

When running on Amazon Managed Service for Apache Flink the runtime configuration is read from *Runtime Properties*.

When running locally, the configuration is read from the [`resources/flink-application-properties-dev.json`](resources/flink-application-properties-dev.json) file located in the resources folder.

Runtime parameters:

| Group ID  | Key                                 | Description                                                                                            | 
|-----------|-------------------------------------|--------------------------------------------------------------------------------------------------------|
| `Output0` | `bootstrap.servers`                 | Kafka/MSK bootstrap servers for SASL/SCRAM authentication (typically with port `9096` on MSK)          |`
| `Output0` | `topic`                             | Name of the output topic                                                                               |
| `Output0` | `credentials.secret`                | Name of the secret (not the ARN) in SecretsManager containing the SASL/SCRAM credentials               |
| `Output0` | `credentials.secret.username.field` | Name of the field (the key) of the secret, containing the SASL username. Optional, default: `username` |
| `Output0` | `credentials.secret.password.field` | Name of the field (the key) of the secret, containing the SASL password. Optional, default: `password` |

Additionally, the following runtime parameters are required if you need to provide a custom TrustStore (the code 
setting up the S3 config provider must also be uncommented)


| Group ID  | Key                                 | Description                                                                                            | 
|-----------|-------------------------------------|--------------------------------------------------------------------------------------------------------|
| `Output0` | `bucket.region`                     | Region of the S3 bucket containing the TrustStore                                                      |
| `Output0` | `truststore.bucket`                 | Name of the S3 bucket containing the TrustStore (without any `s3://` prefix)                           |
| `Output0` | `truststore.path`                   | Path to the TrustStore, in the S3 bucket (without trailing `/` )                                       |


All parameters are case-sensitive.

## Running locally in IntelliJ

> Due to MSK VPC networking, to run this example on your machine you need to set up network connectivity to the VPC where MSK is deployed, for example with a VPN.
> Setting this connectivity depends on your setup and is out of scope for this example.

You can run this example directly in IntelliJ, without any local Flink cluster or local Flink installation.

See [Running examples locally](../running-examples-locally.md) for details.
