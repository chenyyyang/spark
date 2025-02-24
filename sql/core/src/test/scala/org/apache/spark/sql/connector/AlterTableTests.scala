/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.connector

import scala.collection.JavaConverters._

import org.apache.spark.SparkException
import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.analysis.UnresolvedAttribute
import org.apache.spark.sql.catalyst.parser.ParseException
import org.apache.spark.sql.catalyst.util.quoteIdentifier
import org.apache.spark.sql.connector.catalog.CatalogV2Util.withDefaultOwnership
import org.apache.spark.sql.connector.catalog.Table
import org.apache.spark.sql.errors.QueryErrorsBase
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types._

trait AlterTableTests extends SharedSparkSession with QueryErrorsBase {

  protected def getTableMetadata(tableName: String): Table

  protected val catalogAndNamespace: String

  protected val v2Format: String

  private def fullTableName(tableName: String): String = {
    if (catalogAndNamespace.isEmpty) {
      s"default.$tableName"
    } else {
      s"$catalogAndNamespace$tableName"
    }
  }

  test("AlterTable: table does not exist") {
    val t2 = s"${catalogAndNamespace}fake_table"
    val quoted = UnresolvedAttribute.parseAttributeName(s"${catalogAndNamespace}table_name")
      .map(part => quoteIdentifier(part)).mkString(".")
    withTable(t2) {
      sql(s"CREATE TABLE $t2 (id int) USING $v2Format")
      val exc = intercept[AnalysisException] {
        sql(s"ALTER TABLE ${catalogAndNamespace}table_name DROP COLUMN id")
      }

      checkErrorTableNotFound(exc, quoted,
        ExpectedContext(s"${catalogAndNamespace}table_name", 12,
          11 + s"${catalogAndNamespace}table_name".length))
    }
  }

  test("AlterTable: change rejected by implementation") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int) USING $v2Format")

      val exc = intercept[SparkException] {
        sql(s"ALTER TABLE $t DROP COLUMN id")
      }

      assert(exc.getMessage.contains("Unsupported table change"))
      assert(exc.getMessage.contains("Cannot drop all fields")) // from the implementation

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType().add("id", IntegerType))
    }
  }

  test("AlterTable: add top-level column") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int) USING $v2Format")
      sql(s"ALTER TABLE $t ADD COLUMN data string")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType().add("id", IntegerType).add("data", StringType))
    }
  }

  test("AlterTable: add column with NOT NULL") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int) USING $v2Format")
      sql(s"ALTER TABLE $t ADD COLUMN data string NOT NULL")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === StructType(Seq(
        StructField("id", IntegerType),
        StructField("data", StringType, nullable = false))))
    }
  }

  test("AlterTable: add column with comment") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int) USING $v2Format")
      sql(s"ALTER TABLE $t ADD COLUMN data string COMMENT 'doc'")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === StructType(Seq(
        StructField("id", IntegerType),
        StructField("data", StringType).withComment("doc"))))
    }
  }

  test("AlterTable: add column with interval type") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, point struct<x: double, y: double>) USING $v2Format")
      val e1 =
        intercept[ParseException](sql(s"ALTER TABLE $t ADD COLUMN data interval"))
      assert(e1.getMessage.contains("Cannot use interval type in the table schema."))
      val e2 =
        intercept[ParseException](sql(s"ALTER TABLE $t ADD COLUMN point.z interval"))
      assert(e2.getMessage.contains("Cannot use interval type in the table schema."))
    }
  }

  test("AlterTable: add column with position") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (point struct<x: int>) USING $v2Format")

      sql(s"ALTER TABLE $t ADD COLUMN a string FIRST")
      assert(getTableMetadata(t).schema == new StructType()
        .add("a", StringType)
        .add("point", new StructType().add("x", IntegerType)))

      sql(s"ALTER TABLE $t ADD COLUMN b string AFTER point")
      assert(getTableMetadata(t).schema == new StructType()
        .add("a", StringType)
        .add("point", new StructType().add("x", IntegerType))
        .add("b", StringType))

      val e1 = intercept[AnalysisException](
        sql(s"ALTER TABLE $t ADD COLUMN c string AFTER non_exist"))
      checkError(
        exception = e1,
        errorClass = "FIELD_NOT_FOUND",
        parameters = Map("fieldName" -> "`c`", "fields" -> "a, point, b")
      )

      sql(s"ALTER TABLE $t ADD COLUMN point.y int FIRST")
      assert(getTableMetadata(t).schema == new StructType()
        .add("a", StringType)
        .add("point", new StructType()
          .add("y", IntegerType)
          .add("x", IntegerType))
        .add("b", StringType))

      sql(s"ALTER TABLE $t ADD COLUMN point.z int AFTER x")
      assert(getTableMetadata(t).schema == new StructType()
        .add("a", StringType)
        .add("point", new StructType()
          .add("y", IntegerType)
          .add("x", IntegerType)
          .add("z", IntegerType))
        .add("b", StringType))

      val e2 = intercept[AnalysisException](
        sql(s"ALTER TABLE $t ADD COLUMN point.x2 int AFTER non_exist"))
      checkError(
        exception = e2,
        errorClass = "FIELD_NOT_FOUND",
        parameters = Map("fieldName" -> "`x2`", "fields" -> "y, x, z")
      )
    }
  }

  test("SPARK-30814: add column with position referencing new columns being added") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (a string, b int, point struct<x: double, y: double>) USING $v2Format")
      sql(s"ALTER TABLE $t ADD COLUMNS (x int AFTER a, y int AFTER x, z int AFTER y)")

      assert(getTableMetadata(t).schema === new StructType()
        .add("a", StringType)
        .add("x", IntegerType)
        .add("y", IntegerType)
        .add("z", IntegerType)
        .add("b", IntegerType)
        .add("point", new StructType()
          .add("x", DoubleType)
          .add("y", DoubleType)))

      sql(s"ALTER TABLE $t ADD COLUMNS (point.z double AFTER x, point.zz double AFTER z)")
      assert(getTableMetadata(t).schema === new StructType()
        .add("a", StringType)
        .add("x", IntegerType)
        .add("y", IntegerType)
        .add("z", IntegerType)
        .add("b", IntegerType)
        .add("point", new StructType()
          .add("x", DoubleType)
          .add("z", DoubleType)
          .add("zz", DoubleType)
          .add("y", DoubleType)))

      // The new column being referenced should come before being referenced.
      val e = intercept[AnalysisException](
        sql(s"ALTER TABLE $t ADD COLUMNS (yy int AFTER xx, xx int)"))
      checkError(
        exception = e,
        errorClass = "FIELD_NOT_FOUND",
        parameters = Map("fieldName" -> "`yy`", "fields" -> "a, x, y, z, b, point")
      )
    }
  }

  test("AlterTable: add multiple columns") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int) USING $v2Format")
      sql(s"ALTER TABLE $t ADD COLUMNS data string COMMENT 'doc', ts timestamp")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === StructType(Seq(
        StructField("id", IntegerType),
        StructField("data", StringType).withComment("doc"),
        StructField("ts", TimestampType))))
    }
  }

  test("AlterTable: add nested column") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, point struct<x: double, y: double>) USING $v2Format")
      sql(s"ALTER TABLE $t ADD COLUMN point.z double")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType()
        .add("id", IntegerType)
        .add("point", StructType(Seq(
          StructField("x", DoubleType),
          StructField("y", DoubleType),
          StructField("z", DoubleType)))))
    }
  }

  test("AlterTable: add nested column to map key") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, points map<struct<x: double, y: double>, bigint>) " +
        s"USING $v2Format")
      sql(s"ALTER TABLE $t ADD COLUMN points.key.z double")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType()
        .add("id", IntegerType)
        .add("points", MapType(StructType(Seq(
          StructField("x", DoubleType),
          StructField("y", DoubleType),
          StructField("z", DoubleType))), LongType)))
    }
  }

  test("AlterTable: add nested column to map value") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, points map<string, struct<x: double, y: double>>) " +
        s"USING $v2Format")
      sql(s"ALTER TABLE $t ADD COLUMN points.value.z double")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType()
        .add("id", IntegerType)
        .add("points", MapType(StringType, StructType(Seq(
          StructField("x", DoubleType),
          StructField("y", DoubleType),
          StructField("z", DoubleType))))))
    }
  }

  test("AlterTable: add nested column to array element") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, points array<struct<x: double, y: double>>) USING $v2Format")
      sql(s"ALTER TABLE $t ADD COLUMN points.element.z double")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType()
        .add("id", IntegerType)
        .add("points", ArrayType(StructType(Seq(
          StructField("x", DoubleType),
          StructField("y", DoubleType),
          StructField("z", DoubleType))))))
    }
  }

  test("SPARK-39383 DEFAULT columns on V2 data sources with ALTER TABLE ADD/ALTER COLUMN") {
    withSQLConf(SQLConf.DEFAULT_COLUMN_ALLOWED_PROVIDERS.key -> s"$v2Format, ") {
      val t = fullTableName("table_name")
      withTable("t") {
        sql(s"create table $t (a string) using $v2Format")
        sql(s"alter table $t add column (b int default 2 + 3)")

        val table = getTableMetadata(t)

        assert(table.name === t)
        assert(table.schema === new StructType()
          .add("a", StringType)
          .add(StructField("b", IntegerType)
            .withCurrentDefaultValue("2 + 3")
            .withExistenceDefaultValue("5")))

        sql(s"alter table $t alter column b set default 2 + 3")

        assert(
          getTableMetadata(t).schema === new StructType()
            .add("a", StringType)
            .add(StructField("b", IntegerType)
              .withCurrentDefaultValue("2 + 3")
              .withExistenceDefaultValue("5")))

        sql(s"alter table $t alter column b drop default")

        assert(
          getTableMetadata(t).schema === new StructType()
            .add("a", StringType)
            .add(StructField("b", IntegerType)
              .withExistenceDefaultValue("5")))
      }
    }
  }

  test("AlterTable: add complex column") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int) USING $v2Format")
      sql(s"ALTER TABLE $t ADD COLUMN points array<struct<x: double, y: double>>")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType()
        .add("id", IntegerType)
        .add("points", ArrayType(StructType(Seq(
          StructField("x", DoubleType),
          StructField("y", DoubleType))))))
    }
  }

  test("AlterTable: add nested column with comment") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, points array<struct<x: double, y: double>>) USING $v2Format")
      sql(s"ALTER TABLE $t ADD COLUMN points.element.z double COMMENT 'doc'")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType()
        .add("id", IntegerType)
        .add("points", ArrayType(StructType(Seq(
          StructField("x", DoubleType),
          StructField("y", DoubleType),
          StructField("z", DoubleType).withComment("doc"))))))
    }
  }

  test("AlterTable: add nested column parent must exist") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int) USING $v2Format")

      val exc = intercept[AnalysisException] {
        sql(s"ALTER TABLE $t ADD COLUMN point.z double")
      }

      assert(exc.getMessage.contains("Missing field point"))
    }
  }

  test("AlterTable: add column - new column should not exist") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(
        s"""CREATE TABLE $t (
           |id int,
           |point struct<x: double, y: double>,
           |arr array<struct<x: double, y: double>>,
           |mk map<struct<x: double, y: double>, string>,
           |mv map<string, struct<x: double, y: double>>
           |)
           |USING $v2Format""".stripMargin)

      Seq("id", "point.x", "arr.element.x", "mk.key.x", "mv.value.x").foreach { field =>
        val expectedParameters = Map(
          "op" -> "add",
          "fieldNames" -> s"${toSQLId(field)}",
          "struct" ->
            """"STRUCT<id: INT, point: STRUCT<x: DOUBLE, y: DOUBLE>,
              |arr: ARRAY<STRUCT<x: DOUBLE, y: DOUBLE>>,
              |mk: MAP<STRUCT<x: DOUBLE, y: DOUBLE>, STRING>,
              |mv: MAP<STRING, STRUCT<x: DOUBLE, y: DOUBLE>>>"""".stripMargin.replace("\n", " "))
        checkError(
          exception = intercept[AnalysisException] {
            sql(s"ALTER TABLE $t ADD COLUMNS $field double")
          },
          errorClass = "FIELDS_ALREADY_EXISTS",
          parameters = expectedParameters,
          context = ExpectedContext(
            fragment = s"ALTER TABLE $t ADD COLUMNS $field double",
            start = 0,
            stop = 31 + t.length + field.length)
        )
      }
    }
  }

  test("SPARK-36372: Adding duplicate columns should not be allowed") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int) USING $v2Format")
      checkError(
        exception = intercept[AnalysisException] {
          sql(s"ALTER TABLE $t ADD COLUMNS (data string, data1 string, data string)")
        },
        errorClass = "COLUMN_ALREADY_EXISTS",
        parameters = Map("columnName" -> "`data`"))
    }
  }

  test("SPARK-36372: Adding duplicate nested columns should not be allowed") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, point struct<x: double, y: double>) USING $v2Format")
      checkError(
        exception = intercept[AnalysisException] {
          sql(s"ALTER TABLE $t ADD COLUMNS (point.z double, point.z double, point.xx double)")
        },
        errorClass = "COLUMN_ALREADY_EXISTS",
        parameters = Map("columnName" -> toSQLId("point.z")))
    }
  }

  test("AlterTable: update column type int -> long") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int) USING $v2Format")
      sql(s"ALTER TABLE $t ALTER COLUMN id TYPE bigint")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType().add("id", LongType))
    }
  }

  test("AlterTable: update column type to interval") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int) USING $v2Format")
      (DataTypeTestUtils.dayTimeIntervalTypes ++ DataTypeTestUtils.yearMonthIntervalTypes)
        .foreach {
          case d: DataType => d.typeName
            val e = intercept[AnalysisException](
              sql(s"ALTER TABLE $t ALTER COLUMN id TYPE ${d.typeName}"))
            assert(e.getMessage.contains("id to interval type"))
        }
    }
  }

  test("AlterTable: SET/DROP NOT NULL") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id bigint NOT NULL) USING $v2Format")
      sql(s"ALTER TABLE $t ALTER COLUMN id SET NOT NULL")

      val table = getTableMetadata(t)
      assert(table.name === t)
      assert(table.schema === new StructType().add("id", LongType, nullable = false))

      sql(s"ALTER TABLE $t ALTER COLUMN id DROP NOT NULL")
      val table2 = getTableMetadata(t)
      assert(table2.name === t)
      assert(table2.schema === new StructType().add("id", LongType))

      val e = intercept[AnalysisException] {
        sql(s"ALTER TABLE $t ALTER COLUMN id SET NOT NULL")
      }
      assert(e.message.contains("Cannot change nullable column to non-nullable"))
    }
  }

  test("AlterTable: update nested type float -> double") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, point struct<x: float, y: double>) USING $v2Format")
      sql(s"ALTER TABLE $t ALTER COLUMN point.x TYPE double")

      val table = getTableMetadata(t)
      assert(table.name === t)
      assert(table.schema === new StructType()
        .add("id", IntegerType)
        .add("point", StructType(Seq(
          StructField("x", DoubleType),
          StructField("y", DoubleType)))))
    }
  }

  test("AlterTable: update column with struct type fails") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, point struct<x: double, y: double>) USING $v2Format")
      val sqlText =
        s"ALTER TABLE $t ALTER COLUMN point TYPE struct<x: double, y: double, z: double>"

      val fullName = if (catalogAndNamespace.isEmpty) {
        s"spark_catalog.default.table_name"
      } else {
        t
      }

      checkError(
        exception = intercept[AnalysisException] {
          sql(sqlText)
        },
        errorClass = "UPDATE_FIELD_WITH_STRUCT_UNSUPPORTED",
        parameters = Map(
          "table" -> s"${toSQLId(fullName)}",
          "fieldName" -> "`point`"),
        context = ExpectedContext(
          fragment = sqlText,
          start = 0,
          stop = 75 + t.length)
      )

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType()
        .add("id", IntegerType)
        .add("point", StructType(Seq(
          StructField("x", DoubleType),
          StructField("y", DoubleType)))))
    }
  }

  test("AlterTable: update column with array type fails") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, points array<int>) USING $v2Format")

      val exc = intercept[AnalysisException] {
        sql(s"ALTER TABLE $t ALTER COLUMN points TYPE array<long>")
      }

      assert(exc.getMessage.contains("update the element by updating points.element"))

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType()
        .add("id", IntegerType)
        .add("points", ArrayType(IntegerType)))
    }
  }

  test("AlterTable: update column array element type") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, points array<int>) USING $v2Format")
      sql(s"ALTER TABLE $t ALTER COLUMN points.element TYPE long")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType()
        .add("id", IntegerType)
        .add("points", ArrayType(LongType)))
    }
  }

  test("AlterTable: update column with map type fails") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, m map<string, int>) USING $v2Format")

      val exc = intercept[AnalysisException] {
        sql(s"ALTER TABLE $t ALTER COLUMN m TYPE map<string, long>")
      }

      assert(exc.getMessage.contains("update a map by updating m.key or m.value"))

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType()
        .add("id", IntegerType)
        .add("m", MapType(StringType, IntegerType)))
    }
  }

  test("AlterTable: update column map value type") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, m map<string, int>) USING $v2Format")
      sql(s"ALTER TABLE $t ALTER COLUMN m.value TYPE long")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType()
        .add("id", IntegerType)
        .add("m", MapType(StringType, LongType)))
    }
  }

  test("AlterTable: update nested type in map key") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, points map<struct<x: float, y: double>, bigint>) " +
        s"USING $v2Format")
      sql(s"ALTER TABLE $t ALTER COLUMN points.key.x TYPE double")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType()
        .add("id", IntegerType)
        .add("points", MapType(StructType(Seq(
          StructField("x", DoubleType),
          StructField("y", DoubleType))), LongType)))
    }
  }

  test("AlterTable: update nested type in map value") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, points map<string, struct<x: float, y: double>>) " +
        s"USING $v2Format")
      sql(s"ALTER TABLE $t ALTER COLUMN points.value.x TYPE double")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType()
        .add("id", IntegerType)
        .add("points", MapType(StringType, StructType(Seq(
          StructField("x", DoubleType),
          StructField("y", DoubleType))))))
    }
  }

  test("AlterTable: update nested type in array") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, points array<struct<x: float, y: double>>) USING $v2Format")
      sql(s"ALTER TABLE $t ALTER COLUMN points.element.x TYPE double")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType()
        .add("id", IntegerType)
        .add("points", ArrayType(StructType(Seq(
          StructField("x", DoubleType),
          StructField("y", DoubleType))))))
    }
  }

  test("AlterTable: update column must exist") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int) USING $v2Format")

      val exc = intercept[AnalysisException] {
        sql(s"ALTER TABLE $t ALTER COLUMN data TYPE string")
      }

      assert(exc.getMessage.contains("Missing field data"))
    }
  }

  test("AlterTable: nested update column must exist") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int) USING $v2Format")

      val exc = intercept[AnalysisException] {
        sql(s"ALTER TABLE $t ALTER COLUMN point.x TYPE double")
      }

      assert(exc.getMessage.contains("Missing field point.x"))
    }
  }

  test("AlterTable: update column type must be compatible") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int) USING $v2Format")
      val sql1 = s"ALTER TABLE $t ALTER COLUMN id TYPE boolean"
      checkErrorMatchPVals(
        exception = intercept[AnalysisException] {
          sql(sql1)
        },
        errorClass = "NOT_SUPPORTED_CHANGE_COLUMN",
        sqlState = None,
        parameters = Map(
          "originType" -> "\"INT\"",
          "newType" -> "\"BOOLEAN\"",
          "newName" -> "`id`",
          "originName" -> "`id`",
          "table" -> ".*table_name.*"),
        context = ExpectedContext(
          fragment = sql1,
          start = 0,
          stop = sql1.length - 1)
      )
    }
  }

  test("AlterTable: update column comment") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int) USING $v2Format")
      sql(s"ALTER TABLE $t ALTER COLUMN id COMMENT 'doc'")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === StructType(Seq(StructField("id", IntegerType).withComment("doc"))))
    }
  }

  test("AlterTable: update column position") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (a int, b int, point struct<x: int, y: int, z: int>) USING $v2Format")

      sql(s"ALTER TABLE $t ALTER COLUMN b FIRST")
      assert(getTableMetadata(t).schema == new StructType()
        .add("b", IntegerType)
        .add("a", IntegerType)
        .add("point", new StructType()
          .add("x", IntegerType)
          .add("y", IntegerType)
          .add("z", IntegerType)))

      sql(s"ALTER TABLE $t ALTER COLUMN b AFTER point")
      assert(getTableMetadata(t).schema == new StructType()
        .add("a", IntegerType)
        .add("point", new StructType()
          .add("x", IntegerType)
          .add("y", IntegerType)
          .add("z", IntegerType))
        .add("b", IntegerType))

      val e1 = intercept[AnalysisException](
        sql(s"ALTER TABLE $t ALTER COLUMN b AFTER non_exist"))
      assert(e1.getMessage.contains("Missing field non_exist"))

      sql(s"ALTER TABLE $t ALTER COLUMN point.y FIRST")
      assert(getTableMetadata(t).schema == new StructType()
        .add("a", IntegerType)
        .add("point", new StructType()
          .add("y", IntegerType)
          .add("x", IntegerType)
          .add("z", IntegerType))
        .add("b", IntegerType))

      sql(s"ALTER TABLE $t ALTER COLUMN point.y AFTER z")
      assert(getTableMetadata(t).schema == new StructType()
        .add("a", IntegerType)
        .add("point", new StructType()
          .add("x", IntegerType)
          .add("z", IntegerType)
          .add("y", IntegerType))
        .add("b", IntegerType))

      val e2 = intercept[AnalysisException](
        sql(s"ALTER TABLE $t ALTER COLUMN point.y AFTER non_exist"))
      assert(e2.getMessage.contains("Missing field point.non_exist"))

      // `AlterTable.resolved` checks column existence.
      intercept[AnalysisException](
        sql(s"ALTER TABLE $t ALTER COLUMN a.y AFTER x"))
    }
  }

  test("AlterTable: update nested column comment") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, point struct<x: double, y: double>) USING $v2Format")
      sql(s"ALTER TABLE $t ALTER COLUMN point.y COMMENT 'doc'")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType()
        .add("id", IntegerType)
        .add("point", StructType(Seq(
          StructField("x", DoubleType),
          StructField("y", DoubleType).withComment("doc")))))
    }
  }

  test("AlterTable: update nested column comment in map key") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, points map<struct<x: double, y: double>, bigint>) " +
        s"USING $v2Format")
      sql(s"ALTER TABLE $t ALTER COLUMN points.key.y COMMENT 'doc'")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType()
        .add("id", IntegerType)
        .add("points", MapType(StructType(Seq(
          StructField("x", DoubleType),
          StructField("y", DoubleType).withComment("doc"))), LongType)))
    }
  }

  test("AlterTable: update nested column comment in map value") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, points map<string, struct<x: double, y: double>>) " +
        s"USING $v2Format")
      sql(s"ALTER TABLE $t ALTER COLUMN points.value.y COMMENT 'doc'")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType()
        .add("id", IntegerType)
        .add("points", MapType(StringType, StructType(Seq(
          StructField("x", DoubleType),
          StructField("y", DoubleType).withComment("doc"))))))
    }
  }

  test("AlterTable: update nested column comment in array") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, points array<struct<x: double, y: double>>) USING $v2Format")
      sql(s"ALTER TABLE $t ALTER COLUMN points.element.y COMMENT 'doc'")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType()
        .add("id", IntegerType)
        .add("points", ArrayType(StructType(Seq(
          StructField("x", DoubleType),
          StructField("y", DoubleType).withComment("doc"))))))
    }
  }

  test("AlterTable: comment update column must exist") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int) USING $v2Format")

      val exc = intercept[AnalysisException] {
        sql(s"ALTER TABLE $t ALTER COLUMN data COMMENT 'doc'")
      }

      assert(exc.getMessage.contains("Missing field data"))
    }
  }

  test("AlterTable: nested comment update column must exist") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int) USING $v2Format")

      val exc = intercept[AnalysisException] {
        sql(s"ALTER TABLE $t ALTER COLUMN point.x COMMENT 'doc'")
      }

      assert(exc.getMessage.contains("Missing field point.x"))
    }
  }

  test("AlterTable: rename column") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int) USING $v2Format")
      sql(s"ALTER TABLE $t RENAME COLUMN id TO user_id")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType().add("user_id", IntegerType))
    }
  }

  test("AlterTable: rename nested column") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, point struct<x: double, y: double>) USING $v2Format")
      sql(s"ALTER TABLE $t RENAME COLUMN point.y TO t")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType()
        .add("id", IntegerType)
        .add("point", StructType(Seq(
          StructField("x", DoubleType),
          StructField("t", DoubleType)))))
    }
  }

  test("AlterTable: rename nested column in map key") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, point map<struct<x: double, y: double>, bigint>) " +
        s"USING $v2Format")
      sql(s"ALTER TABLE $t RENAME COLUMN point.key.y TO t")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType()
        .add("id", IntegerType)
        .add("point", MapType(StructType(Seq(
          StructField("x", DoubleType),
          StructField("t", DoubleType))), LongType)))
    }
  }

  test("AlterTable: rename nested column in map value") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, points map<string, struct<x: double, y: double>>) " +
        s"USING $v2Format")
      sql(s"ALTER TABLE $t RENAME COLUMN points.value.y TO t")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType()
        .add("id", IntegerType)
        .add("points", MapType(StringType, StructType(Seq(
          StructField("x", DoubleType),
          StructField("t", DoubleType))))))
    }
  }

  test("AlterTable: rename nested column in array element") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, points array<struct<x: double, y: double>>) USING $v2Format")
      sql(s"ALTER TABLE $t RENAME COLUMN points.element.y TO t")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType()
        .add("id", IntegerType)
        .add("points", ArrayType(StructType(Seq(
          StructField("x", DoubleType),
          StructField("t", DoubleType))))))
    }
  }

  test("AlterTable: rename column must exist") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int) USING $v2Format")

      val exc = intercept[AnalysisException] {
        sql(s"ALTER TABLE $t RENAME COLUMN data TO some_string")
      }

      assert(exc.getMessage.contains("Missing field data"))
    }
  }

  test("AlterTable: nested rename column must exist") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int) USING $v2Format")

      val exc = intercept[AnalysisException] {
        sql(s"ALTER TABLE $t RENAME COLUMN point.x TO z")
      }

      assert(exc.getMessage.contains("Missing field point.x"))
    }
  }

  test("AlterTable: rename column - new name should not exist") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(
        s"""CREATE TABLE $t (
           |id int,
           |user_id int,
           |point struct<x: double, y: double>,
           |arr array<struct<x: double, y: double>>,
           |mk map<struct<x: double, y: double>, string>,
           |mv map<string, struct<x: double, y: double>>
           |)
           |USING $v2Format""".stripMargin)

      Seq(
        ("id" -> "user_id") -> "user_id",
        ("point.x" -> "y") -> "point.y",
        ("arr.element.x" -> "y") -> "arr.element.y",
        ("mk.key.x" -> "y") -> "mk.key.y",
        ("mv.value.x" -> "y") -> "mv.value.y").foreach { case ((field, newName), expectedName) =>

        val expectedStruct =
          """"
            |STRUCT<id: INT, user_id: INT,
            | point: STRUCT<x: DOUBLE, y: DOUBLE>,
            | arr: ARRAY<STRUCT<x: DOUBLE, y: DOUBLE>>,
            | mk: MAP<STRUCT<x: DOUBLE, y: DOUBLE>, STRING>,
            | mv: MAP<STRING, STRUCT<x: DOUBLE, y: DOUBLE>>>
            |"""".stripMargin.replace("\n", "")
        val expectedStop = if (expectedName == "user_id") {
          39 + t.length
        } else {
          31 + t.length + expectedName.length
        }

        checkError(
          exception = intercept[AnalysisException] {
            sql(s"ALTER TABLE $t RENAME COLUMN $field TO $newName")
          },
          errorClass = "FIELDS_ALREADY_EXISTS",
          parameters = Map(
            "op" -> "rename",
            "fieldNames" -> s"${toSQLId(expectedName)}",
            "struct" -> expectedStruct),
          context = ExpectedContext(
            fragment = s"ALTER TABLE $t RENAME COLUMN $field TO $newName",
            start = 0,
            stop = expectedStop)
        )
      }
    }
  }

  test("AlterTable: drop column") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, data string) USING $v2Format")
      sql(s"ALTER TABLE $t DROP COLUMN data")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType().add("id", IntegerType))
    }
  }

  test("AlterTable: drop nested column") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, point struct<x: double, y: double, t: double>) " +
        s"USING $v2Format")
      sql(s"ALTER TABLE $t DROP COLUMN point.t")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType()
        .add("id", IntegerType)
        .add("point", StructType(Seq(
          StructField("x", DoubleType),
          StructField("y", DoubleType)))))
    }
  }

  test("AlterTable: drop nested column in map key") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, point map<struct<x: double, y: double>, bigint>) " +
        s"USING $v2Format")
      sql(s"ALTER TABLE $t DROP COLUMN point.key.y")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType()
        .add("id", IntegerType)
        .add("point", MapType(StructType(Seq(
          StructField("x", DoubleType))), LongType)))
    }
  }

  test("AlterTable: drop nested column in map value") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, points map<string, struct<x: double, y: double>>) " +
        s"USING $v2Format")
      sql(s"ALTER TABLE $t DROP COLUMN points.value.y")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType()
        .add("id", IntegerType)
        .add("points", MapType(StringType, StructType(Seq(
          StructField("x", DoubleType))))))
    }
  }

  test("AlterTable: drop nested column in array element") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, points array<struct<x: double, y: double>>) USING $v2Format")
      sql(s"ALTER TABLE $t DROP COLUMN points.element.y")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === new StructType()
        .add("id", IntegerType)
        .add("points", ArrayType(StructType(Seq(
          StructField("x", DoubleType))))))
    }
  }

  test("AlterTable: drop column must exist if required") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int) USING $v2Format")

      val exc = intercept[AnalysisException] {
        sql(s"ALTER TABLE $t DROP COLUMN data")
      }

      assert(exc.getMessage.contains("Missing field data"))

      // with if exists it should pass
      sql(s"ALTER TABLE $t DROP COLUMN IF EXISTS data")
      val table = getTableMetadata(t)
      assert(table.schema == new StructType().add("id", IntegerType))
    }
  }

  test("AlterTable: nested drop column must exist if required") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int) USING $v2Format")

      val exc = intercept[AnalysisException] {
        sql(s"ALTER TABLE $t DROP COLUMN point.x")
      }

      assert(exc.getMessage.contains("Missing field point.x"))

      // with if exists it should pass
      sql(s"ALTER TABLE $t DROP COLUMN IF EXISTS point.x")
      val table = getTableMetadata(t)
      assert(table.schema == new StructType().add("id", IntegerType))
    }
  }

  test("AlterTable: drop mixed existing/non-existing columns using IF EXISTS") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int, name string, points array<struct<x: double, y: double>>) " +
        s"USING $v2Format")

      // with if exists it should pass
      sql(s"ALTER TABLE $t DROP COLUMNS IF EXISTS " +
        s"names, name, points.element.z, id, points.element.x")
      val table = getTableMetadata(t)
      assert(table.schema == new StructType()
        .add("points", ArrayType(StructType(Seq(StructField("y", DoubleType))))))
    }
  }

  test("AlterTable: set table property") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int) USING $v2Format")
      sql(s"ALTER TABLE $t SET TBLPROPERTIES ('test'='34')")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.properties ===
        withDefaultOwnership(Map("provider" -> v2Format, "test" -> "34")).asJava)
    }
  }

  test("AlterTable: remove table property") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (id int) USING $v2Format TBLPROPERTIES('test' = '34')")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.properties ===
        withDefaultOwnership(Map("provider" -> v2Format, "test" -> "34")).asJava)

      sql(s"ALTER TABLE $t UNSET TBLPROPERTIES ('test')")

      val updated = getTableMetadata(t)

      assert(updated.name === t)
      assert(updated.properties === withDefaultOwnership(Map("provider" -> v2Format)).asJava)
    }
  }

  test("AlterTable: replace columns") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (col1 int, col2 int COMMENT 'c2') USING $v2Format")
      sql(s"ALTER TABLE $t REPLACE COLUMNS (col2 string, col3 int COMMENT 'c3')")

      val table = getTableMetadata(t)

      assert(table.name === t)
      assert(table.schema === StructType(Seq(
        StructField("col2", StringType),
        StructField("col3", IntegerType).withComment("c3"))))
    }
  }

  test("SPARK-36449: Replacing columns with duplicate name should not be allowed") {
    val t = fullTableName("table_name")
    withTable(t) {
      sql(s"CREATE TABLE $t (data string) USING $v2Format")
      checkError(
        exception = intercept[AnalysisException] {
          sql(s"ALTER TABLE $t REPLACE COLUMNS (data string, data1 string, data string)")
        },
        errorClass = "COLUMN_ALREADY_EXISTS",
        parameters = Map("columnName" -> "`data`"))
    }
  }
}
