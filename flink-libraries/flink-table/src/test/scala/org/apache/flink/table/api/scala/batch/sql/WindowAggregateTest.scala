/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.api.scala.batch.sql

import java.sql.Timestamp

import org.apache.flink.api.scala._
import org.apache.flink.table.api.{TableException, ValidationException}
import org.apache.flink.table.api.java.utils.UserDefinedAggFunctions.WeightedAvgWithMerge
import org.apache.flink.table.api.scala._
import org.apache.flink.table.plan.logical._
import org.apache.flink.table.utils.TableTestBase
import org.apache.flink.table.utils.TableTestUtil._
import org.junit.Test

class WindowAggregateTest extends TableTestBase {

  @Test
  def testNonPartitionedTumbleWindow(): Unit = {
    val util = batchTestUtil()
    util.addTable[(Int, Long, String, Timestamp)]("T", 'a, 'b, 'c, 'ts)

    val sqlQuery =
      "SELECT SUM(a) AS sumA, COUNT(b) AS cntB FROM T GROUP BY TUMBLE(ts, INTERVAL '2' HOUR)"

    val expected =
      unaryNode(
        "DataSetWindowAggregate",
        unaryNode(
          "DataSetCalc",
          batchTableNode(0),
          term("select", "ts, a, b")
        ),
        term("window", TumblingGroupWindow('w$, 'ts, 7200000.millis)),
        term("select", "SUM(a) AS sumA, COUNT(b) AS cntB")
      )

    util.verifySql(sqlQuery, expected)
  }

  @Test
  def testPartitionedTumbleWindow(): Unit = {
    val util = batchTestUtil()
    util.addTable[(Int, Long, String, Timestamp)]("T", 'a, 'b, 'c, 'ts)

    val sqlQuery =
      "SELECT " +
        "  TUMBLE_START(ts, INTERVAL '4' MINUTE), " +
        "  TUMBLE_END(ts, INTERVAL '4' MINUTE), " +
        "  c, " +
        "  SUM(a) AS sumA, " +
        "  MIN(b) AS minB " +
        "FROM T " +
        "GROUP BY TUMBLE(ts, INTERVAL '4' MINUTE), c"

    val expected =
      unaryNode(
        "DataSetCalc",
        unaryNode(
          "DataSetWindowAggregate",
          batchTableNode(0),
          term("groupBy", "c"),
          term("window", TumblingGroupWindow('w$, 'ts, 240000.millis)),
          term("select", "c, SUM(a) AS sumA, MIN(b) AS minB, " +
            "start('w$) AS w$start, end('w$) AS w$end")
        ),
        term("select", "CAST(w$start) AS w$start, CAST(w$end) AS w$end, c, sumA, minB")
      )

    util.verifySql(sqlQuery, expected)
  }

  @Test
  def testTumbleWindowWithUdAgg() = {
    val util = batchTestUtil()
    util.addTable[(Int, Long, String, Timestamp)]("T", 'a, 'b, 'c, 'ts)

    val weightedAvg = new WeightedAvgWithMerge
    util.tEnv.registerFunction("weightedAvg", weightedAvg)

    val sql = "SELECT weightedAvg(b, a) AS wAvg " +
      "FROM T " +
      "GROUP BY TUMBLE(ts, INTERVAL '4' MINUTE)"

    val expected =
      unaryNode(
        "DataSetWindowAggregate",
        unaryNode(
          "DataSetCalc",
          batchTableNode(0),
          term("select", "ts, b, a")
        ),
        term("window", TumblingGroupWindow('w$, 'ts, 240000.millis)),
        term("select", "weightedAvg(b, a) AS wAvg")
      )

    util.verifySql(sql, expected)
  }

  @Test
  def testNonPartitionedHopWindow(): Unit = {
    val util = batchTestUtil()
    util.addTable[(Int, Long, String, Timestamp)]("T", 'a, 'b, 'c, 'ts)

    val sqlQuery =
      "SELECT SUM(a) AS sumA, COUNT(b) AS cntB " +
        "FROM T " +
        "GROUP BY HOP(ts, INTERVAL '15' MINUTE, INTERVAL '90' MINUTE)"

    val expected =
      unaryNode(
        "DataSetWindowAggregate",
        unaryNode(
          "DataSetCalc",
          batchTableNode(0),
          term("select", "ts, a, b")
        ),
        term("window",
          SlidingGroupWindow('w$, 'ts, 5400000.millis, 900000.millis)),
        term("select", "SUM(a) AS sumA, COUNT(b) AS cntB")
      )

    util.verifySql(sqlQuery, expected)
  }

  @Test
  def testPartitionedHopWindow(): Unit = {
    val util = batchTestUtil()
    util.addTable[(Int, Long, String, Long, Timestamp)]("T", 'a, 'b, 'c, 'd, 'ts)

    val sqlQuery =
      "SELECT " +
        "  c, " +
        "  HOP_END(ts, INTERVAL '1' HOUR, INTERVAL '3' HOUR), " +
        "  HOP_START(ts, INTERVAL '1' HOUR, INTERVAL '3' HOUR), " +
        "  SUM(a) AS sumA, " +
        "  AVG(b) AS avgB " +
        "FROM T " +
        "GROUP BY HOP(ts, INTERVAL '1' HOUR, INTERVAL '3' HOUR), d, c"

    val expected =
      unaryNode(
        "DataSetCalc",
        unaryNode(
          "DataSetWindowAggregate",
          batchTableNode(0),
          term("groupBy", "c, d"),
          term("window",
            SlidingGroupWindow('w$, 'ts, 10800000.millis, 3600000.millis)),
          term("select", "c, d, SUM(a) AS sumA, AVG(b) AS avgB, " +
            "start('w$) AS w$start, end('w$) AS w$end")
        ),
        term("select", "c, CAST(w$end) AS w$end, CAST(w$start) AS w$start, sumA, avgB")
      )

    util.verifySql(sqlQuery, expected)
  }

  @Test
  def testNonPartitionedSessionWindow(): Unit = {
    val util = batchTestUtil()
    util.addTable[(Int, Long, String, Timestamp)]("T", 'a, 'b, 'c, 'ts)

    val sqlQuery =
      "SELECT COUNT(*) AS cnt FROM T GROUP BY SESSION(ts, INTERVAL '30' MINUTE)"

    val expected =
      unaryNode(
        "DataSetWindowAggregate",
        unaryNode(
          "DataSetCalc",
          batchTableNode(0),
          term("select", "ts")
        ),
        term("window", SessionGroupWindow('w$, 'ts, 1800000.millis)),
        term("select", "COUNT(*) AS cnt")
      )

    util.verifySql(sqlQuery, expected)
  }

  @Test
  def testPartitionedSessionWindow(): Unit = {
    val util = batchTestUtil()
    util.addTable[(Int, Long, String, Int, Timestamp)]("T", 'a, 'b, 'c, 'd, 'ts)

    val sqlQuery =
      "SELECT " +
        "  c, d, " +
        "  SESSION_START(ts, INTERVAL '12' HOUR), " +
        "  SESSION_END(ts, INTERVAL '12' HOUR), " +
        "  SUM(a) AS sumA, " +
        "  MIN(b) AS minB " +
        "FROM T " +
        "GROUP BY SESSION(ts, INTERVAL '12' HOUR), c, d"

    val expected =
      unaryNode(
        "DataSetCalc",
        unaryNode(
          "DataSetWindowAggregate",
          batchTableNode(0),
          term("groupBy", "c, d"),
          term("window", SessionGroupWindow('w$, 'ts, 43200000.millis)),
          term("select", "c, d, SUM(a) AS sumA, MIN(b) AS minB, " +
            "start('w$) AS w$start, end('w$) AS w$end")
        ),
        term("select", "c, d, CAST(w$start) AS w$start, CAST(w$end) AS w$end, sumA, minB")
      )

    util.verifySql(sqlQuery, expected)
  }

  @Test
  def testWindowEndOnly(): Unit = {
    val util = batchTestUtil()
    util.addTable[(Int, Long, String, Timestamp)]("T", 'a, 'b, 'c, 'ts)

    val sqlQuery =
      "SELECT " +
        "  TUMBLE_END(ts, INTERVAL '4' MINUTE)" +
        "FROM T " +
        "GROUP BY TUMBLE(ts, INTERVAL '4' MINUTE), c"

    val expected =
      unaryNode(
        "DataSetCalc",
        unaryNode(
          "DataSetWindowAggregate",
          unaryNode(
            "DataSetCalc",
            batchTableNode(0),
            term("select", "ts, c")
          ),
          term("groupBy", "c"),
          term("window", TumblingGroupWindow('w$, 'ts, 240000.millis)),
          term("select", "c, start('w$) AS w$start, end('w$) AS w$end")
        ),
        term("select", "CAST(w$end) AS w$end")
      )

    util.verifySql(sqlQuery, expected)
  }

  @Test(expected = classOf[TableException])
  def testTumbleWindowNoOffset(): Unit = {
    val util = batchTestUtil()
    util.addTable[(Int, Long, String, Timestamp)]("T", 'a, 'b, 'c, 'ts)

    val sqlQuery =
      "SELECT SUM(a) AS sumA, COUNT(b) AS cntB " +
        "FROM T " +
        "GROUP BY TUMBLE(ts, INTERVAL '2' HOUR, TIME '10:00:00')"

    util.verifySql(sqlQuery, "n/a")
  }

  @Test(expected = classOf[TableException])
  def testHopWindowNoOffset(): Unit = {
    val util = batchTestUtil()
    util.addTable[(Int, Long, String, Timestamp)]("T", 'a, 'b, 'c, 'ts)

    val sqlQuery =
      "SELECT SUM(a) AS sumA, COUNT(b) AS cntB " +
        "FROM T " +
        "GROUP BY HOP(ts, INTERVAL '1' HOUR, INTERVAL '2' HOUR, TIME '10:00:00')"

    util.verifySql(sqlQuery, "n/a")
  }

  @Test(expected = classOf[TableException])
  def testSessionWindowNoOffset(): Unit = {
    val util = batchTestUtil()
    util.addTable[(Int, Long, String, Timestamp)]("T", 'a, 'b, 'c, 'ts)

    val sqlQuery =
      "SELECT SUM(a) AS sumA, COUNT(b) AS cntB " +
        "FROM T " +
        "GROUP BY SESSION(ts, INTERVAL '2' HOUR, TIME '10:00:00')"

    util.verifySql(sqlQuery, "n/a")
  }

  @Test(expected = classOf[TableException])
  def testVariableWindowSize() = {
    val util = batchTestUtil()
    util.addTable[(Int, Long, String, Timestamp)]("T", 'a, 'b, 'c, 'ts)

    val sql = "SELECT COUNT(*) " +
      "FROM T " +
      "GROUP BY TUMBLE(ts, b * INTERVAL '1' MINUTE)"
    util.verifySql(sql, "n/a")
  }

  @Test(expected = classOf[ValidationException])
  def testTumbleWindowWithInvalidUdAggArgs() = {
    val util = batchTestUtil()
    util.addTable[(Int, Long, String, Timestamp)]("T", 'a, 'b, 'c, 'ts)

    val weightedAvg = new WeightedAvgWithMerge
    util.tEnv.registerFunction("weightedAvg", weightedAvg)

    val sql = "SELECT weightedAvg(c, a) AS wAvg " +
      "FROM T " +
      "GROUP BY TUMBLE(ts, INTERVAL '4' MINUTE)"
    util.verifySql(sql, "n/a")
  }
}
