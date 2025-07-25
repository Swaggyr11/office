#!/bin/bash

$SPARK_HOME/bin/spark-submit \
  --master k8s://https://<EKS_CLUSTER_ENDPOINT> \
  --deploy-mode cluster \
  --name spark-s3-student-filter \
  --class StudentFilterApp \
  --conf spark.executor.instances=2 \
  --conf spark.kubernetes.container.image=<ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com/spark-s3-job \
  --conf spark.kubernetes.namespace=default \
  --conf spark.kubernetes.authenticate.driver.serviceAccountName=spark \
  --conf spark.hadoop.fs.s3a.aws.credentials.provider=com.amazonaws.auth.DefaultAWSCredentialsProviderChain \
  --conf spark.hadoop.fs.s3a.impl=org.apache.hadoop.fs.s3a.S3AFileSystem \
  local:///opt/spark/jars/SparkS3CSVFilter-assembly-0.1.jar
