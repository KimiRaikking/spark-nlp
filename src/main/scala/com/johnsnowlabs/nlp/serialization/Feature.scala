package com.johnsnowlabs.nlp.serialization

import com.johnsnowlabs.nlp.HasFeatures
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.sql.types.{ArrayType, StringType}
import org.apache.spark.sql.{Encoder, Encoders, SparkSession}

import scala.reflect.ClassTag

abstract class Feature[Serializable1, Serializable2, TComplete](model: HasFeatures, val name: String, val description: String)(implicit val sparkSession: SparkSession = SparkSession.builder().getOrCreate()) extends Serializable {
  model.features.append(this)

  final protected var value: Broadcast[Option[TComplete]] = sparkSession.sparkContext.broadcast[Option[TComplete]](None)

  def serialize(spark: SparkSession, path: String, field: String, value: TComplete): Unit

  final def serializeInfer(spark: SparkSession, path: String, field: String, value: Any): Unit =
    serialize(spark, path, field, value.asInstanceOf[TComplete])

  def deserialize(spark: SparkSession, path: String, field: String): Option[_]

  final protected def getFieldPath(path: String, field: String): Path =
    Path.mergePaths(new Path(path), new Path("/fields/" + field))

  final def get: Option[TComplete] = value.value
  final def getValue: TComplete = value.value.getOrElse(throw new Exception(s"feature $name is not set"))
  final def setValue(v: Option[Any]): HasFeatures = {value.unpersist(false); value = sparkSession.sparkContext.broadcast(Some(v.get.asInstanceOf[TComplete])); model}
  final def isSet: Boolean = value.value.isDefined

}

class StructFeature[TValue: ClassTag](model: HasFeatures, override val name: String, override val  description: String)
  extends Feature[TValue, TValue, TValue](model, name, description) {

  implicit val encoder: Encoder[TValue] = Encoders.kryo[TValue]

  override def serialize(spark: SparkSession, path: String, field: String, value: TValue): Unit = {
    import spark.sqlContext.implicits._
    val dataPath = getFieldPath(path, field)
    Seq(value.asInstanceOf[TValue]).toDS.write.mode("overwrite").parquet(dataPath.toString)
  }

  override def deserialize(spark: SparkSession, path: String, field: String): Option[TValue] = {
    val fs: FileSystem = FileSystem.get(spark.sparkContext.hadoopConfiguration)
    val dataPath = getFieldPath(path, field)
    if (fs.exists(dataPath)) {
      val loaded = spark.read.parquet(dataPath.toString)
      import spark.implicits._
      loaded.schema.head.dataType match {
        case ArrayType(StringType, _) => loaded.as[String].collect.headOption.map(_.asInstanceOf[TValue])
        case _ => loaded.as[TValue].collect.headOption
      }
    } else {
      None
    }
  }

}

class MapFeature[TKey: ClassTag, TValue: ClassTag](model: HasFeatures, override val name: String, override val description: String)
  extends Feature[TKey, TValue, Map[TKey, TValue]](model, name, description) {

  implicit val encoder: Encoder[Map[TKey, TValue]] = Encoders.kryo[Map[TKey, TValue]]

  override def serialize(spark: SparkSession, path: String, field: String, value: Map[TKey, TValue]): Unit = {
    import spark.sqlContext.implicits._
    val dataPath = getFieldPath(path, field)
    Seq(value).toDS.write.mode("overwrite").parquet(dataPath.toString)
  }

  override def deserialize(spark: SparkSession, path: String, field: String): Option[Map[TKey, TValue]] = {
    val fs: FileSystem = FileSystem.get(spark.sparkContext.hadoopConfiguration)
    val dataPath = getFieldPath(path, field)
    if (fs.exists(dataPath)) {
      val loaded = spark.read.parquet(dataPath.toString)
      loaded.as[Map[TKey, TValue]].collect.headOption
    } else {
      None
    }
  }

}

class ArrayFeature[TValue: ClassTag](model: HasFeatures, override val name: String, override val description: String)
  extends Feature[TValue, TValue, Array[TValue]](model, name, description) {

  implicit val encoder: Encoder[TValue] = Encoders.kryo[TValue]

  override def serialize(spark: SparkSession, path: String, field: String, value: Array[TValue]): Unit = {
    import spark.sqlContext.implicits._
    val dataPath = getFieldPath(path, field)
    Seq(value.toSeq.toDS.write.mode("overwrite").parquet(dataPath.toString))
  }

  override def deserialize(spark: SparkSession, path: String, field: String): Option[Array[TValue]] = {
    val fs: FileSystem = FileSystem.get(spark.sparkContext.hadoopConfiguration)
    val dataPath = getFieldPath(path, field)
    if (fs.exists(dataPath)) {
      val loaded = spark.read.parquet(dataPath.toString)
      Some(loaded.as[TValue].collect)
    } else {
      None
    }
  }

}

