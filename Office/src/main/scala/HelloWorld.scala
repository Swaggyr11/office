import org.apache.spark.sql.SparkSession

object HelloWorld {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder
      .appName("Spark HelloWorld")
      .master("local[*]")
      .getOrCreate()

    import spark.implicits._

    val data = Seq("Hello", "World", "from", "Spark", "3.5.1", "Scala", "2.12.18")
    val df = data.toDF("word")
    df.show()

    spark.stop()
  }
}
