import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

object StudentFilterApp {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("Student CSV Filter")
      .config("spark.hadoop.fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
      .config("spark.hadoop.fs.s3a.aws.credentials.provider",
        "com.amazonaws.auth.DefaultAWSCredentialsProviderChain")
      .getOrCreate()

    val inputPath = "s3a://your-bucket/input/students.csv"
    val outputPath = "s3a://your-bucket/output/students_filtered.csv"

    val df = spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .csv(inputPath)

    val filtered = df.filter(col("age") > 10)

    filtered.write
      .option("header", "true")
      .mode("overwrite")
      .csv(outputPath)

    spark.stop()
  }
}
