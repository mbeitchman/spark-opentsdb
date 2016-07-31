/*
 * Copyright 2016 CGnal S.p.A.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cgnal.spark

import java.nio.ByteBuffer
import java.util.{ Calendar, TimeZone }

import net.opentsdb.core.TSDB
import net.opentsdb.utils.Config
import org.apache.hadoop.hbase.client.Scan
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp
import org.apache.hadoop.hbase.filter.{ RegexStringComparator, RowFilter }
import org.hbase.async.HBaseClient
import shapeless.{ :+:, CNil, Coproduct }

import scala.annotation.switch
import scala.collection.mutable.ArrayBuffer

package object opentsdb {

  type Value = Byte :+: Int :+: Long :+: Float :+: Double :+: CNil

  implicit val writeForByte: (Iterator[(String, Long, Byte, Map[String, String])], TSDB) => Unit = (it, tsdb) => {
    import collection.JavaConversions._
    it.foreach(record => {
      tsdb.addPoint(record._1, record._2, record._3.asInstanceOf[Long], record._4)
    })
  }

  implicit val writeForInt: (Iterator[(String, Long, Int, Map[String, String])], TSDB) => Unit = (it, tsdb) => {
    import collection.JavaConversions._
    it.foreach(record => {
      tsdb.addPoint(record._1, record._2, record._3.asInstanceOf[Long], record._4)
    })
  }

  implicit val writeForLong: (Iterator[(String, Long, Long, Map[String, String])], TSDB) => Unit = (it, tsdb) => {
    import collection.JavaConversions._
    it.foreach(record => {
      tsdb.addPoint(record._1, record._2, record._3, record._4)
    })
  }

  implicit val writeForFloat: (Iterator[(String, Long, Float, Map[String, String])], TSDB) => Unit = (it, tsdb) => {
    import collection.JavaConversions._
    it.foreach(record => {
      tsdb.addPoint(record._1, record._2, record._3, record._4)
    })
  }

  implicit val writeForDouble: (Iterator[(String, Long, Double, Map[String, String])], TSDB) => Unit = (it, tsdb) => {
    import collection.JavaConversions._
    it.foreach(record => {
      tsdb.addPoint(record._1, record._2, record._3, record._4)
    })
  }

  private[opentsdb] object TSDBClientManager {

    var quorum: String = _

    var port: String = _

    var tsdbTable: String = _

    var tsdbUidTable: String = _

    lazy val tsdb: TSDB = {
      val hbaseAsyncClient = new HBaseClient(s"$quorum:$port", "/hbase")
      val config = new Config(false)
      config.overrideConfig("tsd.storage.hbase.data_table", tsdbTable)
      config.overrideConfig("tsd.storage.hbase.uid_table", tsdbUidTable)
      config.overrideConfig("tsd.core.auto_create_metrics", "true")
      new TSDB(hbaseAsyncClient, config)
    }

    def apply(quorum: String, port: String, tsdbTable: String, tsdbUidTable: String): Unit = {
      this.quorum = quorum
      this.port = port
      this.tsdbTable = tsdbTable
      this.tsdbUidTable = tsdbUidTable
    }

  }

  private[opentsdb] def getMetricScan(
    tags: Map[String, String],
    metricsUID: Array[Array[Byte]],
    tagKUIDs: Map[String, Array[Byte]],
    tagVUIDs: Map[String, Array[Byte]],
    startdate: Option[String],
    enddate: Option[String],
    dateFormat: String
  ) = {
    val tagKKeys = tagKUIDs.keys.toArray
    val tagVKeys = tagVUIDs.keys.toArray
    val ntags = tags.filter(kv => tagKKeys.contains(kv._1) && tagVKeys.contains(kv._2))
    val tagKV = tagKUIDs.
      filter(kv => ntags.contains(kv._1)).
      map(k => (k._2, tagVUIDs(tags(k._1)))).
      map(l => l._1 ++ l._2).toList.sorted(Ordering.by((_: Array[Byte]).toIterable))
    val scan = new Scan()
    val name = if (tagKV.nonEmpty)
      String.format("^%s.*%s.*$", bytes2hex(metricsUID.last, "\\x"), bytes2hex(tagKV.flatten.toArray, "\\x"))
    else
      String.format("^%s.*$", bytes2hex(metricsUID.last, "\\x"))

    val keyRegEx: RegexStringComparator = new RegexStringComparator(name)
    val rowFilter: RowFilter = new RowFilter(CompareOp.EQUAL, keyRegEx)
    scan.setFilter(rowFilter)

    val simpleDateFormat = new java.text.SimpleDateFormat(dateFormat)
    simpleDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"))

    val minDate = new Calendar.Builder().setTimeZone(TimeZone.getTimeZone("UTC")).setDate(1970, 0, 1).setTimeOfDay(0, 0, 0).build().getTime
    val maxDate = new Calendar.Builder().setTimeZone(TimeZone.getTimeZone("UTC")).setDate(2099, 11, 31).setTimeOfDay(23, 59, 0).build().getTime

    val stDateBuffer = ByteBuffer.allocate(4)
    stDateBuffer.putInt((simpleDateFormat.parse(if (startdate.isDefined) startdate.get else simpleDateFormat.format(minDate)).getTime / 1000).toInt)

    val endDateBuffer = ByteBuffer.allocate(4)
    endDateBuffer.putInt((simpleDateFormat.parse(if (enddate.isDefined) enddate.get else simpleDateFormat.format(maxDate)).getTime / 1000).toInt)

    scan.setStartRow(hexStringToByteArray(bytes2hex(metricsUID.last, "\\x") + bytes2hex(stDateBuffer.array(), "\\x") + bytes2hex(tagKV.flatten.toArray, "\\x")))
    scan.setStopRow(hexStringToByteArray(bytes2hex(metricsUID.last, "\\x") + bytes2hex(endDateBuffer.array(), "\\x") + bytes2hex(tagKV.flatten.toArray, "\\x")))
    scan
  }

  private[opentsdb] def getUIDScan(metricName: String, tags: Map[String, String]) = {
    val scan = new Scan()
    val name: String = String.format("^%s$", Array(metricName, tags.keys.mkString("|"), tags.values.mkString("|")).mkString("|"))
    val keyRegEx: RegexStringComparator = new RegexStringComparator(name)
    val rowFilter: RowFilter = new RowFilter(CompareOp.EQUAL, keyRegEx)
    scan.setFilter(rowFilter)
    scan
  }

  //TODO: changes operations on binary strings to bits
  private[opentsdb] def processQuantifier(quantifier: Array[Byte]): Array[(Long, Boolean, Int)] = {
    //converting Byte Arrays to a Array of binary string
    val q = quantifier.map({ v => Integer.toBinaryString(v & 255 | 256).substring(1) })
    var i = 0
    val out = new ArrayBuffer[(Long, Boolean, Int)]
    while (i != q.length) {
      var value: Long = -1
      var isInteger = true
      var valueLength = -1
      var isQuantifierSizeTypeSmall = true
      //If the 1st 4 bytes are in format "1111", the size of the column quantifier is 4 bytes. Else 2 bytes
      if (q(i).startsWith("1111")) {
        isQuantifierSizeTypeSmall = false
      }

      if (isQuantifierSizeTypeSmall) {
        val v = q(i) + q(i + 1).substring(0, 4) //The 1st 12 bits represent the delta
        value = Integer.parseInt(v, 2).toLong //convert the delta to Int (seconds)
        isInteger = q(i + 1).substring(4, 5) == "0" //The 13th bit represent the format of the value for the delta. 0=Integer, 1=Float
        valueLength = Integer.parseInt(q(i + 1).substring(5, 8), 2) //The last 3 bits represents the length of the value
        i = i + 2
      } else {
        val v = q(i).substring(4, 8) + q(i + 1) + q(i + 2) + q(i + 3).substring(0, 2) //The first 4 bits represents the size, the next 22 bits hold the delta
        value = Integer.parseInt(v, 2).toLong //convert the delta to Int (milliseconds -> seconds)
        isInteger = q(i + 3).substring(4, 5) == "0" //The 29th bit represent the format of the value for the delta. 0=Integer, 1=Float
        valueLength = Integer.parseInt(q(i + 3).substring(5, 8), 2) //The last 3 bits represents the length of the value
        i = i + 4
      }
      out += ((value, isInteger, valueLength + 1))
    }
    out.toArray
  }

  private[opentsdb] def processValues(quantifier: Array[(Long, Boolean, Int)], values: Array[Byte]): Array[Value] = {
    val out = new ArrayBuffer[Value]
    var i = 0
    var j = 0
    while (j < quantifier.length) {
      //Is the value represented as integer or float
      val isInteger = quantifier(j)._2
      //The number of Byte in which the value has been encoded
      val valueSize = quantifier(j)._3
      //Get the value for the current delta
      val valueBytes = values.slice(i, i + valueSize)
      val value = if (!isInteger) {
        (valueSize: @switch) match {
          case 4 =>
            Coproduct[Value](ByteBuffer.wrap(valueBytes).getFloat())
          case 8 =>
            Coproduct[Value](ByteBuffer.wrap(valueBytes).getDouble())
        }
      } else {
        (valueSize: @switch) match {
          case 1 =>
            Coproduct[Value](valueBytes(0))
          case 4 =>
            Coproduct[Value](ByteBuffer.wrap(valueBytes).getInt())
          case 8 =>
            Coproduct[Value](ByteBuffer.wrap(valueBytes).getLong())
        }
      }
      i += valueSize
      j += 1
      out += value
    }
    out.toArray
  }

  private def bytes2hex(bytes: Array[Byte], sep: String): String = {
    sep + bytes.map("%02x".format(_)).mkString(sep)
  }

  private def hexStringToByteArray(s: String): Array[Byte] = {
    val sn = s.replace("\\x", "")
    val b: Array[Byte] = new Array[Byte](sn.length / 2)
    var i: Int = 0
    while (i < b.length) {
      {
        val index: Int = i * 2
        val v: Int = Integer.parseInt(sn.substring(index, index + 2), 16)
        b(i) = v.toByte
      }
      {
        i += 1
        i - 1
      }
    }
    b
  }

}