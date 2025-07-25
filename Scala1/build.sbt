name := "SparkS3CSVFilter"

version := "0.1"

scalaVersion := "2.12.18"

libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-core" % "3.5.1" % "provided",
  "org.apache.spark" %% "spark-sql" % "3.5.1" % "provided",
  "org.apache.hadoop" % "hadoop-aws" % "3.3.4"
)

assemblyMergeStrategy in assembly := {
  case PathList("META-INF", _ @_*) => MergeStrategy.discard
  case _ => MergeStrategy.first
}

enablePlugins(AssemblyPlugin)
