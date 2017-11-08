package cl.klukva

import scala.util.Try
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.count
import org.apache.spark.sql.types.{StringType, StructType, TimestampType}
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe
import org.apache.spark.streaming.kafka010.KafkaUtils
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent
import org.apache.spark.streaming.{Seconds, StreamingContext}


object KafkaSparkLocal {
  def main(args: Array[String]): Unit = {
    // Configurations for kafka consumer
    val kafkaBrokers = sys.env.get("KAFKA_BROKERS")
    val kafkaGroupId = sys.env.get("KAFKA_GROUP_ID")
    val kafkaTopic = sys.env.get("KAFKA_TOPIC")

    // Verify that all settings are set
    require(kafkaBrokers.isDefined, "KAFKA_BROKERS has not been set")
    require(kafkaGroupId.isDefined, "KAFKA_GROUP_ID has not been set")
    require(kafkaTopic.isDefined, "KAFKA_TOPIC has not been set")

    // Create Spark Session
    val spark = SparkSession
      .builder()
      .appName("KafkaSparkLocal")
      .getOrCreate()

    import spark.implicits._

    // Create Streaming Context and Kafka Direct Stream with provided settings and 10 seconds batches
    val ssc = new StreamingContext(spark.sparkContext, Seconds(10))

    val kafkaParams = Map[String, Object](
      "bootstrap.servers" -> kafkaBrokers.get,
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> kafkaGroupId.get,
      "auto.offset.reset" -> "latest"
    )

    val topics = Array(kafkaTopic.get)
    val stream = KafkaUtils.createDirectStream[String, String](
      ssc,
      PreferConsistent,
      Subscribe[String, String](topics, kafkaParams)
    )

    // Process batches:
    // Parse JSON and create Data Frame
    // Execute computation on that Data Frame and print result
    stream.foreachRDD { (rdd, time) =>
      val data = rdd.map(record => record.value)
      val json = spark.read.json(data)
      try {
        val df = json.toDF()
        // Write data to .json file on local filesytem
        df.write.partitionBy("type").mode("append").json("json")
        // Write data to .parquet file on local filesytem
		df.write.partitionBy("type").mode("append").parquet("parquet")
        // Write data to .parquet file on local filesytem using hdfs protocol
        df.write.partitionBy("type").mode("append").format("parquet").save("file:///hdfs")
        // If in the topic there is no data, wait for some data
      } catch {
          case e: Exception => println("waiting for some data")
        }
      
    }

    // Start Stream
    ssc.start()
    ssc.awaitTermination()
  }
}
