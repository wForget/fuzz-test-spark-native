package cn.wangz.spark.fuzz

import cn.wangz.spark.fuzz.NativeEngineType.NativeEngineType
import org.apache.spark.SparkConf
import org.apache.spark.sql.{RuntimeConfig, SparkSession}

class NativeEngineConf(engineType: NativeEngineType) {

  def engineName: String = engineType.toString

  def sparkConf: SparkConf = {
    val conf = new SparkConf()
    engineType match {
      case NativeEngineType.BLAZE =>
        conf.setIfMissing("spark.blaze.enable", "true")
          .setIfMissing("spark.sql.extensions", "org.apache.spark.sql.blaze.BlazeSparkSessionExtension")
          .setIfMissing("spark.shuffle.manager", "org.apache.spark.sql.execution.blaze.shuffle.BlazeShuffleManager")
          .setIfMissing("spark.executor.memory", "3g")
          .setIfMissing("spark.memory.offHeap.enabled", "false")
      case NativeEngineType.COMET =>
        conf.setIfMissing("spark.comet.enabled", "true")
          .setIfMissing("spark.plugins", "org.apache.spark.CometPlugin")
          .setIfMissing("spark.shuffle.manager", "org.apache.spark.sql.comet.execution.shuffle.CometShuffleManager")
          .setIfMissing("spark.comet.explainFallback.enabled", "true")
          .setIfMissing("spark.executor.memory", "1g")
          .setIfMissing("spark.memory.offHeap.enabled", "true")
          .setIfMissing("spark.memory.offHeap.size", "2g")
      case NativeEngineType.GLUTEN =>
        conf.setIfMissing("spark.gluten.enabled", "true")
          .setIfMissing("spark.plugins", "org.apache.gluten.GlutenPlugin")
          .setIfMissing("spark.shuffle.manager", "org.apache.spark.shuffle.sort.ColumnarShuffleManager")
          .setIfMissing("spark.executor.memory", "1g")
          .setIfMissing("spark.memory.offHeap.enabled", "true")
          .setIfMissing("spark.memory.offHeap.size", "2g")
    }
  }

  def disableNativeEngine(spark: SparkSession): Unit = {
    engineType match {
      case NativeEngineType.BLAZE =>
        spark.conf.set("spark.blaze.enable", "false")
      case NativeEngineType.COMET =>
        spark.conf.set("spark.comet.enabled", "false")
      case NativeEngineType.GLUTEN =>
        spark.conf.set("spark.gluten.enabled", "false")
    }
  }

  def enableNativeEngine(spark: SparkSession): Unit = {
    engineType match {
      case NativeEngineType.BLAZE =>
        spark.conf.set("spark.blaze.enable", "true")
      case NativeEngineType.COMET =>
        spark.conf.set("spark.comet.enabled", "true")
      case NativeEngineType.GLUTEN =>
        spark.conf.set("spark.gluten.enabled", "true")
    }
  }

}

object NativeEngineConf {
  def apply(engineType: String): NativeEngineConf = {
    new NativeEngineConf(NativeEngineType.withName(engineType))
  }
}

object NativeEngineType extends Enumeration {
  type NativeEngineType = Value
  val BLAZE: Value = Value("blaze")
  val COMET: Value = Value("comet")
  val GLUTEN: Value = Value("gluten")
}
