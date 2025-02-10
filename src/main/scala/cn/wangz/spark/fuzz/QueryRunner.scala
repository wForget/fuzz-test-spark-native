/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package cn.wangz.spark.fuzz

import org.apache.spark.sql.{Row, SparkSession}

import java.io.{BufferedWriter, FileWriter, PrintWriter, StringWriter}
import java.util.concurrent.atomic.AtomicInteger
import scala.io.Source

object QueryRunner {

  def runQueries(
      spark: SparkSession,
      nativeEngineConf: NativeEngineConf,
      numFiles: Int,
      filename: String,
      showMatchingResults: Boolean,
      showFailedSparkQueries: Boolean = false): Unit = {

    val outputFilename = s"results.md"
    // scalastyle:off println
    println(s"Writing results to $outputFilename")
    // scalastyle:on println

    val w = new BufferedWriter(new FileWriter(outputFilename))

    // register input files
    w.write("## Table Schema\n")
    for (i <- 0 until numFiles) {
      val table = spark.read.parquet(s"test$i.parquet")
      val tableName = s"test$i"
      table.createTempView(tableName)
      w.write("```\n")
      w.write(
        s"Created table $tableName with schema:\n\t" +
          s"${table.schema.fields.map(f => s"${f.name}: ${f.dataType}").mkString("\n\t")}\n\n")
      w.write("```\n")
      w.write("\n")
    }

    val querySource = Source.fromFile(filename)
    var diffCount = 0;
    var errorCount = 0;
    try {
      querySource
        .getLines()
        .foreach(sql => {
          try {
            println(s"Running query: $sql")

            // execute with Spark
            nativeEngineConf.disableNativeEngine(spark)
            val df = spark.sql(sql)
            val sparkRows = df.collect()
            val sparkPlan = df.queryExecution.executedPlan.toString

            try {
              // execute with native engion
              nativeEngineConf.enableNativeEngine(spark)
              val df = spark.sql(sql)
              val nativeEngineRows = df.collect()
              val nativeEnginePlan = df.queryExecution.executedPlan.toString

              if (sparkRows.length == nativeEngineRows.length) {
                var i = 0
                while (i < sparkRows.length) {
                  val l = sparkRows(i)
                  val r = nativeEngineRows(i)
                  assert(l.length == r.length)
                  for (j <- 0 until l.length) {
                    val same = (l(j), r(j)) match {
                      case (a: Float, b: Float) if a.isInfinity => b.isInfinity
                      case (a: Float, b: Float) if a.isNaN => b.isNaN
                      case (a: Float, b: Float) => (a - b).abs <= 0.000001f
                      case (a: Double, b: Double) if a.isInfinity => b.isInfinity
                      case (a: Double, b: Double) if a.isNaN => b.isNaN
                      case (a: Double, b: Double) => (a - b).abs <= 0.000001
                      case (a: Array[Byte], b: Array[Byte]) => a.sameElements(b)
                      case (a, b) => a == b
                    }
                    if (!same) {
                      showSQL(w, sql)
                      showPlans(w, sparkPlan, nativeEnginePlan, nativeEngineConf)
                      w.write(s"First difference at row $i:\n")
                      w.write("Spark: `" + formatRow(l) + "`\n")
                      w.write(s"Native engine ${nativeEngineConf.engineName}: `" + formatRow(r) + "`\n")
                      diffCount += 1
                      i = sparkRows.length
                    }
                  }
                  i += 1
                }
              } else {
                showSQL(w, sql)
                showPlans(w, sparkPlan, nativeEnginePlan, nativeEngineConf)
                w.write(
                  s"[ERROR] Spark produced ${sparkRows.length} rows and " +
                    s"Native engine ${nativeEngineConf.engineName} produced ${nativeEngineRows.length} rows.\n")
                diffCount += 1
              }
            } catch {
              case e: Throwable =>
                // the query worked in Spark but failed in Native engine, so this is likely a bug in Native engine
                showSQL(w, sql)

                w.write("### Error Message\n")
                w.write("```\n")
                w.write(s"[ERROR] Query failed in native engine ${nativeEngineConf.engineName}: ${e.getMessage}:\n")
                w.write("```\n")

                w.write("### Error StackTrace\n")
                w.write("```\n")
                val sw = new StringWriter()
                val p = new PrintWriter(sw)
                e.printStackTrace(p)
                p.close()
                w.write(s"${sw.toString}\n")
                w.write("```\n")
            }

            // flush after every query so that results are saved in the event of the driver crashing
            w.flush()

          } catch {
            case e: Throwable =>
              // we expect many generated queries to be invalid
              if (showFailedSparkQueries) {
                showSQL(w, sql)
                w.write(s"Query failed in Spark: ${e.getMessage}\n")
              }
          }
        })

    } finally {
      w.close()
      querySource.close()
    }
  }

  private def formatRow(row: Row): String = {
    row.toSeq
      .map {
        case null => "NULL"
        case v: Array[Byte] => v.mkString
        case other => other.toString
      }
      .mkString(",")
  }

  private val sqlNum: AtomicInteger = new AtomicInteger(0)
  private def showSQL(w: BufferedWriter, sql: String, maxLength: Int = 120): Unit = {
    w.write(s"## SQL ${sqlNum.incrementAndGet()}\n")
    w.write("```\n")
    val words = sql.split(" ")
    val currentLine = new StringBuilder
    for (word <- words) {
      if (currentLine.length + word.length + 1 > maxLength) {
        w.write(currentLine.toString.trim)
        w.write("\n")
        currentLine.setLength(0)
      }
      currentLine.append(word).append(" ")
    }
    if (currentLine.nonEmpty) {
      w.write(currentLine.toString.trim)
      w.write("\n")
    }
    w.write("```\n")
  }

  private def showPlans(w: BufferedWriter, sparkPlan: String, nativeEnginePlan: String, nativeEngineConf: NativeEngineConf): Unit = {
    w.write("### Spark Plan\n")
    w.write(s"```\n$sparkPlan\n```\n")
    w.write(s"### Native engine ${nativeEngineConf.engineName} Plan\n")
    w.write(s"```\n$nativeEnginePlan\n```\n")
  }

}
