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

package org.apache.spark.sql.hive

import org.apache.hadoop.hive.serde.serdeConstants

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.analysis.UnresolvedAttribute
import org.apache.spark.sql.catalyst.catalog.{CatalogColumn, CatalogTable, CatalogTableType}
import org.apache.spark.sql.catalyst.dsl.expressions._
import org.apache.spark.sql.catalyst.dsl.plans
import org.apache.spark.sql.catalyst.dsl.plans.DslLogicalPlan
import org.apache.spark.sql.catalyst.expressions.JsonTuple
import org.apache.spark.sql.catalyst.parser.ParseException
import org.apache.spark.sql.catalyst.plans.PlanTest
import org.apache.spark.sql.catalyst.plans.logical.{Generate, ScriptTransformation}
import org.apache.spark.sql.hive.execution.{HiveNativeCommand, HiveSqlParser}

class HiveDDLCommandSuite extends PlanTest {
  val parser = HiveSqlParser

  private def extractTableDesc(sql: String): (CatalogTable, Boolean) = {
    parser.parsePlan(sql).collect {
      case CreateTableAsSelect(desc, _, allowExisting) => (desc, allowExisting)
      case CreateViewAsSelect(desc, _, allowExisting, _, _) => (desc, allowExisting)
    }.head
  }

  test("Test CTAS #1") {
    val s1 =
      """CREATE EXTERNAL TABLE IF NOT EXISTS mydb.page_view
        |(viewTime INT,
        |userid BIGINT,
        |page_url STRING,
        |referrer_url STRING,
        |ip STRING COMMENT 'IP Address of the User',
        |country STRING COMMENT 'country of origination')
        |COMMENT 'This is the staging page view table'
        |PARTITIONED BY (dt STRING COMMENT 'date type', hour STRING COMMENT 'hour of the day')
        |ROW FORMAT DELIMITED FIELDS TERMINATED BY '\054' STORED AS RCFILE
        |LOCATION '/user/external/page_view'
        |TBLPROPERTIES ('p1'='v1', 'p2'='v2')
        |AS SELECT * FROM src""".stripMargin

    val (desc, exists) = extractTableDesc(s1)
    assert(exists)
    assert(desc.identifier.database == Some("mydb"))
    assert(desc.identifier.table == "page_view")
    assert(desc.tableType == CatalogTableType.EXTERNAL_TABLE)
    assert(desc.storage.locationUri == Some("/user/external/page_view"))
    assert(desc.schema ==
      CatalogColumn("viewtime", "int") ::
      CatalogColumn("userid", "bigint") ::
      CatalogColumn("page_url", "string") ::
      CatalogColumn("referrer_url", "string") ::
      CatalogColumn("ip", "string", comment = Some("IP Address of the User")) ::
      CatalogColumn("country", "string", comment = Some("country of origination")) :: Nil)
    // TODO will be SQLText
    assert(desc.viewText == Option("This is the staging page view table"))
    assert(desc.viewOriginalText.isEmpty)
    assert(desc.partitionColumns ==
      CatalogColumn("dt", "string", comment = Some("date type")) ::
      CatalogColumn("hour", "string", comment = Some("hour of the day")) :: Nil)
    assert(desc.storage.serdeProperties ==
      Map((serdeConstants.SERIALIZATION_FORMAT, "\u002C"), (serdeConstants.FIELD_DELIM, "\u002C")))
    assert(desc.storage.inputFormat == Some("org.apache.hadoop.hive.ql.io.RCFileInputFormat"))
    assert(desc.storage.outputFormat == Some("org.apache.hadoop.hive.ql.io.RCFileOutputFormat"))
    assert(desc.storage.serde ==
      Some("org.apache.hadoop.hive.serde2.columnar.LazyBinaryColumnarSerDe"))
    assert(desc.properties == Map(("p1", "v1"), ("p2", "v2")))
  }

  test("Test CTAS #2") {
    val s2 =
      """CREATE EXTERNAL TABLE IF NOT EXISTS mydb.page_view
        |(viewTime INT,
        |userid BIGINT,
        |page_url STRING,
        |referrer_url STRING,
        |ip STRING COMMENT 'IP Address of the User',
        |country STRING COMMENT 'country of origination')
        |COMMENT 'This is the staging page view table'
        |PARTITIONED BY (dt STRING COMMENT 'date type', hour STRING COMMENT 'hour of the day')
        |ROW FORMAT SERDE 'parquet.hive.serde.ParquetHiveSerDe'
        | STORED AS
        | INPUTFORMAT 'parquet.hive.DeprecatedParquetInputFormat'
        | OUTPUTFORMAT 'parquet.hive.DeprecatedParquetOutputFormat'
        |LOCATION '/user/external/page_view'
        |TBLPROPERTIES ('p1'='v1', 'p2'='v2')
        |AS SELECT * FROM src""".stripMargin

    val (desc, exists) = extractTableDesc(s2)
    assert(exists)
    assert(desc.identifier.database == Some("mydb"))
    assert(desc.identifier.table == "page_view")
    assert(desc.tableType == CatalogTableType.EXTERNAL_TABLE)
    assert(desc.storage.locationUri == Some("/user/external/page_view"))
    assert(desc.schema ==
      CatalogColumn("viewtime", "int") ::
      CatalogColumn("userid", "bigint") ::
      CatalogColumn("page_url", "string") ::
      CatalogColumn("referrer_url", "string") ::
      CatalogColumn("ip", "string", comment = Some("IP Address of the User")) ::
      CatalogColumn("country", "string", comment = Some("country of origination")) :: Nil)
    // TODO will be SQLText
    assert(desc.viewText == Option("This is the staging page view table"))
    assert(desc.viewOriginalText.isEmpty)
    assert(desc.partitionColumns ==
      CatalogColumn("dt", "string", comment = Some("date type")) ::
      CatalogColumn("hour", "string", comment = Some("hour of the day")) :: Nil)
    assert(desc.storage.serdeProperties == Map())
    assert(desc.storage.inputFormat == Some("parquet.hive.DeprecatedParquetInputFormat"))
    assert(desc.storage.outputFormat == Some("parquet.hive.DeprecatedParquetOutputFormat"))
    assert(desc.storage.serde == Some("parquet.hive.serde.ParquetHiveSerDe"))
    assert(desc.properties == Map(("p1", "v1"), ("p2", "v2")))
  }

  test("Test CTAS #3") {
    val s3 = """CREATE TABLE page_view AS SELECT * FROM src"""
    val (desc, exists) = extractTableDesc(s3)
    assert(exists == false)
    assert(desc.identifier.database == None)
    assert(desc.identifier.table == "page_view")
    assert(desc.tableType == CatalogTableType.MANAGED_TABLE)
    assert(desc.storage.locationUri == None)
    assert(desc.schema == Seq.empty[CatalogColumn])
    assert(desc.viewText == None) // TODO will be SQLText
    assert(desc.viewOriginalText.isEmpty)
    assert(desc.storage.serdeProperties == Map())
    assert(desc.storage.inputFormat == Some("org.apache.hadoop.mapred.TextInputFormat"))
    assert(desc.storage.outputFormat ==
      Some("org.apache.hadoop.hive.ql.io.IgnoreKeyTextOutputFormat"))
    assert(desc.storage.serde.isEmpty)
    assert(desc.properties == Map())
  }

  test("Test CTAS #4") {
    val s4 =
      """CREATE TABLE page_view
        |STORED BY 'storage.handler.class.name' AS SELECT * FROM src""".stripMargin
    intercept[AnalysisException] {
      extractTableDesc(s4)
    }
  }

  test("Test CTAS #5") {
    val s5 = """CREATE TABLE ctas2
               | ROW FORMAT SERDE "org.apache.hadoop.hive.serde2.columnar.ColumnarSerDe"
               | WITH SERDEPROPERTIES("serde_p1"="p1","serde_p2"="p2")
               | STORED AS RCFile
               | TBLPROPERTIES("tbl_p1"="p11", "tbl_p2"="p22")
               | AS
               |   SELECT key, value
               |   FROM src
               |   ORDER BY key, value""".stripMargin
    val (desc, exists) = extractTableDesc(s5)
    assert(exists == false)
    assert(desc.identifier.database == None)
    assert(desc.identifier.table == "ctas2")
    assert(desc.tableType == CatalogTableType.MANAGED_TABLE)
    assert(desc.storage.locationUri == None)
    assert(desc.schema == Seq.empty[CatalogColumn])
    assert(desc.viewText == None) // TODO will be SQLText
    assert(desc.viewOriginalText.isEmpty)
    assert(desc.storage.serdeProperties == Map(("serde_p1" -> "p1"), ("serde_p2" -> "p2")))
    assert(desc.storage.inputFormat == Some("org.apache.hadoop.hive.ql.io.RCFileInputFormat"))
    assert(desc.storage.outputFormat == Some("org.apache.hadoop.hive.ql.io.RCFileOutputFormat"))
    assert(desc.storage.serde == Some("org.apache.hadoop.hive.serde2.columnar.ColumnarSerDe"))
    assert(desc.properties == Map(("tbl_p1" -> "p11"), ("tbl_p2" -> "p22")))
  }

  test("unsupported operations") {
    intercept[ParseException] {
      parser.parsePlan(
        """
          |CREATE TEMPORARY TABLE ctas2
          |ROW FORMAT SERDE "org.apache.hadoop.hive.serde2.columnar.ColumnarSerDe"
          |WITH SERDEPROPERTIES("serde_p1"="p1","serde_p2"="p2")
          |STORED AS RCFile
          |TBLPROPERTIES("tbl_p1"="p11", "tbl_p2"="p22")
          |AS SELECT key, value FROM src ORDER BY key, value
        """.stripMargin)
    }
    intercept[ParseException] {
      parser.parsePlan(
        """CREATE TABLE ctas2
          |STORED AS
          |INPUTFORMAT "org.apache.hadoop.mapred.TextInputFormat"
          |OUTPUTFORMAT "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat"
          |INPUTDRIVER "org.apache.hadoop.hive.howl.rcfile.RCFileInputDriver"
          |OUTPUTDRIVER "org.apache.hadoop.hive.howl.rcfile.RCFileOutputDriver"
          |AS SELECT key, value FROM src ORDER BY key, value
        """.stripMargin)
    }
    intercept[ParseException] {
      parser.parsePlan(
        """
          |CREATE TABLE user_info_bucketed(user_id BIGINT, firstname STRING, lastname STRING)
          |CLUSTERED BY(user_id) INTO 256 BUCKETS
          |AS SELECT key, value FROM src ORDER BY key, value
        """.stripMargin)
    }
    intercept[ParseException] {
      parser.parsePlan(
        """
          |CREATE TABLE user_info_bucketed(user_id BIGINT, firstname STRING, lastname STRING)
          |SKEWED BY (key) ON (1,5,6)
          |AS SELECT key, value FROM src ORDER BY key, value
        """.stripMargin)
    }
    intercept[ParseException] {
      parser.parsePlan(
        """
          |SELECT TRANSFORM (key, value) USING 'cat' AS (tKey, tValue)
          |ROW FORMAT SERDE 'org.apache.hadoop.hive.contrib.serde2.TypedBytesSerDe'
          |RECORDREADER 'org.apache.hadoop.hive.contrib.util.typedbytes.TypedBytesRecordReader'
          |FROM testData
        """.stripMargin)
    }
    intercept[ParseException] {
      parser.parsePlan(
        """
          |CREATE OR REPLACE VIEW IF NOT EXISTS view1 (col1, col3)
          |COMMENT 'blabla'
          |TBLPROPERTIES('prop1Key'="prop1Val")
          |AS SELECT * FROM tab1
        """.stripMargin)
    }
  }

  test("Invalid interval term should throw AnalysisException") {
    def assertError(sql: String, errorMessage: String): Unit = {
      val e = intercept[AnalysisException] {
        parser.parsePlan(sql)
      }
      assert(e.getMessage.contains(errorMessage))
    }
    assertError("select interval '42-32' year to month",
      "month 32 outside range [0, 11]")
    assertError("select interval '5 49:12:15' day to second",
      "hour 49 outside range [0, 23]")
    assertError("select interval '.1111111111' second",
      "nanosecond 1111111111 outside range")
  }

  test("use native json_tuple instead of hive's UDTF in LATERAL VIEW") {
    val plan = parser.parsePlan(
      """
        |SELECT *
        |FROM (SELECT '{"f1": "value1", "f2": 12}' json) test
        |LATERAL VIEW json_tuple(json, 'f1', 'f2') jt AS a, b
      """.stripMargin)

    assert(plan.children.head.asInstanceOf[Generate].generator.isInstanceOf[JsonTuple])
  }

  test("transform query spec") {
    val plan1 = parser.parsePlan("select transform(a, b) using 'func' from e where f < 10")
      .asInstanceOf[ScriptTransformation].copy(ioschema = null)
    val plan2 = parser.parsePlan("map a, b using 'func' as c, d from e")
      .asInstanceOf[ScriptTransformation].copy(ioschema = null)
    val plan3 = parser.parsePlan("reduce a, b using 'func' as (c: int, d decimal(10, 0)) from e")
      .asInstanceOf[ScriptTransformation].copy(ioschema = null)

    val p = ScriptTransformation(
      Seq(UnresolvedAttribute("a"), UnresolvedAttribute("b")),
      "func", Seq.empty, plans.table("e"), null)

    comparePlans(plan1,
      p.copy(child = p.child.where('f < 10), output = Seq('key.string, 'value.string)))
    comparePlans(plan2,
      p.copy(output = Seq('c.string, 'd.string)))
    comparePlans(plan3,
      p.copy(output = Seq('c.int, 'd.decimal(10, 0))))
  }

  test("use backticks in output of Script Transform") {
    parser.parsePlan(
      """SELECT `t`.`thing1`
        |FROM (SELECT TRANSFORM (`parquet_t1`.`key`, `parquet_t1`.`value`)
        |USING 'cat' AS (`thing1` int, `thing2` string) FROM `default`.`parquet_t1`) AS t
      """.stripMargin)
  }

  test("use backticks in output of Generator") {
    parser.parsePlan(
      """
        |SELECT `gentab2`.`gencol2`
        |FROM `default`.`src`
        |LATERAL VIEW explode(array(array(1, 2, 3))) `gentab1` AS `gencol1`
        |LATERAL VIEW explode(`gentab1`.`gencol1`) `gentab2` AS `gencol2`
      """.stripMargin)
  }

  test("use escaped backticks in output of Generator") {
    parser.parsePlan(
      """
        |SELECT `gen``tab2`.`gen``col2`
        |FROM `default`.`src`
        |LATERAL VIEW explode(array(array(1, 2,  3))) `gen``tab1` AS `gen``col1`
        |LATERAL VIEW explode(`gen``tab1`.`gen``col1`) `gen``tab2` AS `gen``col2`
      """.stripMargin)
  }

  test("create view -- basic") {
    val v1 = "CREATE VIEW view1 AS SELECT * FROM tab1"
    val (desc, exists) = extractTableDesc(v1)
    assert(!exists)
    assert(desc.identifier.database.isEmpty)
    assert(desc.identifier.table == "view1")
    assert(desc.tableType == CatalogTableType.VIRTUAL_VIEW)
    assert(desc.storage.locationUri.isEmpty)
    assert(desc.schema == Seq.empty[CatalogColumn])
    assert(desc.viewText == Option("SELECT * FROM tab1"))
    assert(desc.viewOriginalText == Option("SELECT * FROM tab1"))
    assert(desc.storage.serdeProperties == Map())
    assert(desc.storage.inputFormat.isEmpty)
    assert(desc.storage.outputFormat.isEmpty)
    assert(desc.storage.serde.isEmpty)
    assert(desc.properties == Map())
  }

  test("create view - full") {
    val v1 =
      """
        |CREATE OR REPLACE VIEW IF NOT EXISTS view1
        |(col1, col3)
        |TBLPROPERTIES('prop1Key'="prop1Val")
        |AS SELECT * FROM tab1
      """.stripMargin
    val (desc, exists) = extractTableDesc(v1)
    assert(exists)
    assert(desc.identifier.database.isEmpty)
    assert(desc.identifier.table == "view1")
    assert(desc.tableType == CatalogTableType.VIRTUAL_VIEW)
    assert(desc.storage.locationUri.isEmpty)
    assert(desc.schema ==
      CatalogColumn("col1", null, nullable = true, None) ::
        CatalogColumn("col3", null, nullable = true, None) :: Nil)
    assert(desc.viewText == Option("SELECT * FROM tab1"))
    assert(desc.viewOriginalText == Option("SELECT * FROM tab1"))
    assert(desc.storage.serdeProperties == Map())
    assert(desc.storage.inputFormat.isEmpty)
    assert(desc.storage.outputFormat.isEmpty)
    assert(desc.storage.serde.isEmpty)
    assert(desc.properties == Map("prop1Key" -> "prop1Val"))
  }

  test("create view -- partitioned view") {
    val v1 = "CREATE VIEW view1 partitioned on (ds, hr) as select * from srcpart"
    intercept[ParseException] {
      parser.parsePlan(v1).isInstanceOf[HiveNativeCommand]
    }
  }
}
