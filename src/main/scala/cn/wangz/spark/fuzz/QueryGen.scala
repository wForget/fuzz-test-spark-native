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

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types.{DataType, StructType}

import java.io.{BufferedWriter, FileWriter}
import scala.collection.mutable
import scala.util.Random

object QueryGen {

  def generateRandomQueries(
      r: Random,
      spark: SparkSession,
      numFiles: Int,
      numQueries: Int): Unit = {
    for (i <- 0 until numFiles) {
      spark.read.parquet(s"test$i.parquet").createTempView(s"test$i")
    }

    val w = new BufferedWriter(new FileWriter("queries.sql"))

    val uniqueQueries = mutable.HashSet[String]()

    for (_ <- 0 until numQueries) {
      val sql = r.nextInt().abs % 8 match {
        case 0 => generateJoin(r, spark, numFiles)
        case 1 => generateAggregate(r, spark, numFiles)
        case 2 => generateScalar(r, spark, numFiles)
        case 3 => generateCast(r, spark, numFiles)
        case 4 => generateUnaryArithmetic(r, spark, numFiles)
        case 5 => generateBinaryArithmetic(r, spark, numFiles)
        case 6 => generateBinaryComparison(r, spark, numFiles)
        case _ => generateConditional(r, spark, numFiles)
      }
      if (!uniqueQueries.contains(sql)) {
        uniqueQueries += sql
        w.write(sql + "\n")
      }
    }
    w.close()
  }

  private def randomChoiceFunction(functions: Seq[Function], schema: StructType, r: Random): (Function, Seq[String]) = {
    val func = Utils.randomChoice(functions, r)
    val args = func match {
      case FunctionWithSignature(_, _, _, args) =>
        val fields = schema.fields
        args.map {
          case ScalarValueType(_, generateValueFunc) =>
            generateValueFunc()
          case t: WithSupportedType =>
            Utils.randomChoice(fields.filter(f => t.isSupported(f.dataType)).map(f => f.name), r)
          case argType =>
            Utils.randomChoice(fields.filter(f => f.dataType == argType).map(f => f.name), r)
        }
      case _ =>
        Range(0, func.num_args).map(_ => Utils.randomChoice(schema.fieldNames, r))
    }
    (func, args)
  }

  private def generateAggregate(r: Random, spark: SparkSession, numFiles: Int): String = {
    val tableName = s"test${r.nextInt(numFiles)}"
    val table = spark.table(tableName)

    val (func, args) = randomChoiceFunction(Meta.aggFunc, table.schema, r)

    val groupingCols = Range(0, 2).map(_ => Utils.randomChoice(table.columns, r))

    if (groupingCols.isEmpty) {
      s"SELECT ${args.mkString(", ")}, ${func.name}(${args.mkString(", ")}) AS x " +
        s"FROM $tableName " +
        s"ORDER BY ${args.mkString(", ")};"
    } else {
      s"SELECT ${groupingCols.mkString(", ")}, ${func.name}(${args.mkString(", ")}) " +
        s"FROM $tableName " +
        s"GROUP BY ${groupingCols.mkString(",")} " +
        s"ORDER BY ${groupingCols.mkString(", ")};"
    }
  }

  private def generateScalar(r: Random, spark: SparkSession, numFiles: Int): String = {
    val tableName = s"test${r.nextInt(numFiles)}"
    val table = spark.table(tableName)

    val (func, args) = randomChoiceFunction(Meta.scalarFunc, table.schema, r)

    // Example SELECT c0, log(c0) as x FROM test0
    s"SELECT ${args.mkString(", ")}, ${func.name}(${args.mkString(", ")}) AS x " +
      s"FROM $tableName " +
      s"ORDER BY ${args.mkString(", ")};"
  }

  private def generateUnaryArithmetic(r: Random, spark: SparkSession, numFiles: Int): String = {
    val tableName = s"test${r.nextInt(numFiles)}"
    val table = spark.table(tableName)

    val op = Utils.randomChoice(Meta.unaryArithmeticOps, r)
    val a = Utils.randomChoice(table.columns, r)

    // Example SELECT a, -a FROM test0
    s"SELECT $a, $op$a " +
      s"FROM $tableName " +
      s"ORDER BY $a;"
  }

  private def generateBinaryArithmetic(r: Random, spark: SparkSession, numFiles: Int): String = {
    val tableName = s"test${r.nextInt(numFiles)}"
    val table = spark.table(tableName)

    val op = Utils.randomChoice(Meta.binaryArithmeticOps, r)
    val a = Utils.randomChoice(table.columns, r)
    val b = Utils.randomChoice(table.columns, r)

    // Example SELECT a, b, a+b FROM test0
    s"SELECT $a, $b, $a $op $b " +
      s"FROM $tableName " +
      s"ORDER BY $a, $b;"
  }

  private def generateBinaryComparison(r: Random, spark: SparkSession, numFiles: Int): String = {
    val tableName = s"test${r.nextInt(numFiles)}"
    val table = spark.table(tableName)

    val op = Utils.randomChoice(Meta.comparisonOps, r)
    val a = Utils.randomChoice(table.columns, r)
    val b = Utils.randomChoice(table.columns, r)

    // Example SELECT a, b, a <=> b FROM test0
    s"SELECT $a, $b, $a $op $b " +
      s"FROM $tableName " +
      s"ORDER BY $a, $b;"
  }

  private def generateConditional(r: Random, spark: SparkSession, numFiles: Int): String = {
    val tableName = s"test${r.nextInt(numFiles)}"
    val table = spark.table(tableName)

    val op = Utils.randomChoice(Meta.comparisonOps, r)
    val a = Utils.randomChoice(table.columns, r)
    val b = Utils.randomChoice(table.columns, r)

    // Example SELECT a, b, IF(a <=> b, 1, 2), CASE WHEN a <=> b THEN 1 ELSE 2 END FROM test0
    s"SELECT $a, $b, $a $op $b, IF($a $op $b, 1, 2), CASE WHEN $a $op $b THEN 1 ELSE 2 END " +
      s"FROM $tableName " +
      s"ORDER BY $a, $b;"
  }

  private def generateCast(r: Random, spark: SparkSession, numFiles: Int): String = {
    val tableName = s"test${r.nextInt(numFiles)}"
    val table = spark.table(tableName)

    val toType = Utils.randomWeightedChoice(Meta.dataTypes, r).sql
    val arg = Utils.randomChoice(table.columns, r)

    // We test both `cast` and `try_cast` to cover LEGACY and TRY eval modes. It is not
    // recommended to run Comet Fuzz with ANSI enabled currently.
    // Example SELECT c0, cast(c0 as float), try_cast(c0 as float) FROM test0
    s"SELECT $arg, cast($arg as $toType), try_cast($arg as $toType) " +
      s"FROM $tableName " +
      s"ORDER BY $arg;"
  }

  private def generateJoin(r: Random, spark: SparkSession, numFiles: Int): String = {
    val leftTableName = s"test${r.nextInt(numFiles)}"
    val rightTableName = s"test${r.nextInt(numFiles)}"
    val leftTable = spark.table(leftTableName)
    val rightTable = spark.table(rightTableName)

    val leftCol = Utils.randomChoice(leftTable.columns, r)
    val rightCol = Utils.randomChoice(rightTable.columns, r)

    val joinTypes = Seq(("INNER", 0.4), ("LEFT", 0.3), ("RIGHT", 0.3))
    val joinType = Utils.randomWeightedChoice(joinTypes, r)

    val leftColProjection = leftTable.columns.map(c => s"l.$c").mkString(", ")
    val rightColProjection = rightTable.columns.map(c => s"r.$c").mkString(", ")
    "SELECT " +
      s"$leftColProjection, " +
      s"$rightColProjection " +
      s"FROM $leftTableName l " +
      s"$joinType JOIN $rightTableName r " +
      s"ON l.$leftCol = r.$rightCol " +
      "ORDER BY " +
      s"$leftColProjection, " +
      s"$rightColProjection;"
  }

}

trait FunctionTrait {
  def name: String
  def num_args: Int
}

class Function(override val name: String, override val num_args: Int) extends FunctionTrait

object Function {
  def apply(name: String, num_args: Int): Function = new Function(name, num_args)
}

case class FunctionWithSignature(
    override val name: String,
    override val num_args: Int,
    ret: DataType,
    args: Seq[DataType]) extends Function(name, num_args)

trait FuzzDataType {
  def defaultSize: Int = throw new UnsupportedOperationException("defaultSize is not supported")
  def asNullable: DataType = throw new UnsupportedOperationException("asNullable is not supported")
}

trait WithSupportedType {
  def isSupported(dataType: DataType): Boolean
}

case class ScalarValueType(valueType: DataType, generateValueFunc: () => String)
  extends DataType with FuzzDataType

case class MultipleDataTypes(dataTypes: Seq[DataType]) extends DataType with FuzzDataType with WithSupportedType {
  override def isSupported(dataType: DataType): Boolean = dataTypes.contains(dataType)
}

case class AllDataTypes() extends DataType with FuzzDataType with WithSupportedType {
  override def isSupported(dataType: DataType): Boolean = true
}
