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

package com.cgnal.spark.opentsdb

import java.io.File
import java.nio.file.{ Files, Paths }
import java.sql.Timestamp
import java.util

import net.opentsdb.core.{ IllegalDataException, Internal, TSDB }
import org.apache.hadoop.hbase.client.Result
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.util.Bytes
import org.apache.log4j.Logger
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types._
import org.apache.spark.sql.{ DataFrame, Row, SparkSession }
import org.apache.spark.streaming.dstream.DStream
import shaded.org.hbase.async.KeyValue

import scala.collection.convert.decorateAsScala._
import scala.collection.mutable.ListBuffer
import scala.language.{ higherKinds, postfixOps, reflectiveCalls }

/**
 * A class representing a single datapoint
 *
 * @param metric    the metric name the data point belongs to
 * @param timestamp the data point's timestamp
 * @param value     tha value
 * @param tags      the metric tags
 * @tparam T the actual value type
 */
final case class DataPoint[T <: AnyVal](metric: String, timestamp: Long, value: T, tags: Map[String, String]) extends Serializable

/**
 * This companion object is used for carrying important TSDB configuration properties
 */
@SuppressWarnings(Array("org.wartremover.warts.Var"))
object OpenTSDBContext {

  /**
   * The HBase table containing the metrics
   */
  var tsdbTable: String = "tsdb"

  /**
   * The HBase table containing the various IDs for tags and metric names
   */
  var tsdbUidTable: String = "tsdb-uid"

  /**
   * The auto create metrics flag
   */
  var autoCreateMetrics: Boolean = true

  /**
   * The salting prefix width, currently it can be 0=NO SALTING or 1
   */
  var saltWidth: Int = 0

  /**
   * The number of salting buckets
   */
  var saltBuckets: Int = 0

  /**
   *
   */
  var metricWidth: Int = 3

  /**
   *
   */
  var tagkWidth: Int = 3

  /**
   *
   */
  var tagvWidth: Int = 3

  /**
   *
   */
  var preloadUidCache: Boolean = false

  /**
   *
   */
  var preloadUidCacheMaxEntries: Int = 300000
}

/**
 * This class provides all the functionalities for reading and writing metrics from/to an OpenTSDB instance
 *
 * @param sparkSession The sparkSession needed for creating the dataframes, the spark context it's obtained from this sql context
 * @param configurator The Configurator instance that will be used to create the configuration
 */
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments", "org.wartremover.warts.Equals", "org.wartremover.warts.ImplicitParameter", "org.wartremover.warts.Overloading"))
class OpenTSDBContext(@transient val sparkSession: SparkSession, configurator: OpenTSDBConfigurator = DefaultSourceConfigurator) extends Serializable {

  @transient private lazy val log = Logger.getLogger(getClass.getName)

  private lazy val hbaseConfiguration = configurator.configuration

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private[opentsdb] var tsdbTable = OpenTSDBContext.tsdbTable

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private[opentsdb] var tsdbUidTable = OpenTSDBContext.tsdbUidTable

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private[opentsdb] var autoCreateMetrics = OpenTSDBContext.autoCreateMetrics

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private[opentsdb] var saltWidth: Int = OpenTSDBContext.saltWidth

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private[opentsdb] var saltBuckets: Int = OpenTSDBContext.saltBuckets

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private[opentsdb] var tagkWidth: Int = OpenTSDBContext.tagkWidth

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private[opentsdb] var tagvWidth: Int = OpenTSDBContext.tagvWidth

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private[opentsdb] var metricWidth: Int = OpenTSDBContext.metricWidth

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private[opentsdb] var preloadUidCache: Boolean = OpenTSDBContext.preloadUidCache

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private[opentsdb] var preloadUidCacheMaxEntries: Int = OpenTSDBContext.preloadUidCacheMaxEntries

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var keytabData_ : Option[Broadcast[Array[Byte]]] = None

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var keytabLocalTempDir_ : Option[String] = None

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var principal_ : Option[String] = None

  /**
   * @return the keytab path for accessing the secure HBase
   */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def keytab: Broadcast[Array[Byte]] = keytabData_.getOrElse(throw new Exception("keytab has not been defined"))

  /**
   * @param keytab the path of the file containing the keytab
   */
  def keytab_=(keytab: String): Unit = {
    val keytabPath = new File(keytab).getAbsolutePath
    val byteArray = Files.readAllBytes(Paths.get(keytabPath))
    keytabData_ = Some(sparkSession.sparkContext.broadcast(byteArray))
  }

  /**
   * @return
   */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def keytabLocalTempDir: String = keytabLocalTempDir_.getOrElse(throw new Exception("keytabLocalTempDir has not been defined"))

  def keytabLocalTempDir_=(dir: String): Unit = keytabLocalTempDir_ = Some(dir)

  /**
   * @return the Kerberos principal
   */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def principal: String = principal_.getOrElse(throw new Exception("principal has not been defined"))

  /**
   * @param principal the kerberos principal to be used in combination with the keytab
   */
  def principal_=(principal: String): Unit = principal_ = Some(principal)

  /**
   * This method loads a time series from OpenTSDB as a [[org.apache.spark.sql.DataFrame]]
   *
   * @param metricName the metric name
   * @param tags       the metric tags
   * @param interval   an optional pair of longs, the first long is the epoch time in seconds as the beginning of the interval,
   *                   the second long is the end of the interval (exclusive).
   *                   This method will retrieve all the metrics included into this interval.
   * @return the data frame
   */
  def loadDataFrame(
    metricName: String,
    tags: Map[String, String] = Map.empty[String, String],
    interval: Option[(Long, Long)] = None
  ): DataFrame = {
    val schema = StructType(
      Array(
        StructField("timestamp", TimestampType, nullable = false),
        StructField("metric", StringType, nullable = false),
        StructField("value", DoubleType, nullable = false),
        StructField("tags", DataTypes.createMapType(StringType, StringType), nullable = false)
      )
    )

    val rowRDD = load(metricName, tags, interval, ConvertToDouble).map[Row] {
      dp =>
        Row(
          new Timestamp(dp.timestamp),
          dp.metric,
          dp.value.asInstanceOf[Double],
          dp.tags
        )
    }
    sparkSession.createDataFrame(rowRDD, schema)
  }

  /**
   * This method creates a list of metric names
   *
   * @param metrics the list of metrics
   */
  def createMetrics(metrics: List[(String, Map[String, String])]): Unit = {
    val rdd: RDD[Int] = sparkSession.sparkContext.parallelize[Int](1 to 1, 1)

    val toexecute = rdd.mapPartitionsWithIndex[Int]((index, iterator) => {
      TSDBClientManager.init(
        keytabLocalTempDir = keytabLocalTempDir_,
        keytabData = keytabData_,
        principal = principal_,
        baseConf = hbaseConfiguration,
        tsdbTable = tsdbTable,
        tsdbUidTable = tsdbUidTable,
        autoCreateMetrics = autoCreateMetrics,
        saltWidth = saltWidth,
        saltBuckets = saltBuckets,
        metricWidth = metricWidth,
        tagkWidth = tagkWidth,
        tagvWidth = tagvWidth,
        preloadUidCache = preloadUidCache,
        preloadUidCacheMaxEntries = preloadUidCacheMaxEntries
      )
      new Iterator[Int] {

        val tsdb: TSDB = TSDBClientManager.pool.borrowObject()

        @SuppressWarnings(Array("org.wartremover.warts.Var"))
        var firstTime = true

        @SuppressWarnings(Array("org.wartremover.warts.Overloading"))
        override def hasNext: Boolean =
          if (firstTime) {
            firstTime = false
            true
          } else {
            TSDBClientManager.pool.returnObject(tsdb)
            firstTime
          }

        @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
        override def next(): Int = {
          if (index == 0) {
            metrics.foreach(metric => {
              try {
                tsdb.assignUid("metric", metric._1)
              } catch {
                case _: java.lang.IllegalArgumentException =>
              }
              metric._2.foreach[Unit](kv => {
                try {
                  val _ = tsdb.assignUid("tagk", kv._1)
                } catch {
                  case _: java.lang.IllegalArgumentException =>
                }
                try {
                  val _ = tsdb.assignUid("tagv", kv._2)
                } catch {
                  case _: java.lang.IllegalArgumentException =>
                }
              })
            })
          }
          index
        }
      }
    }, preservesPartitioning = true)

    val _ = toexecute.count()
  }

  /**
   * This method loads a time series from OpenTSDB as a [[RDD]][ [[DataPoint]] ]
   *
   * @param metricName         the metric name
   * @param tags               the metric tags
   * @param interval           an optional pair of longs, the first long is the epoch time in seconds as the beginning of the interval,
   *                           the second long is the end of the interval (exclusive).
   *                           This method will retrieve all the metrics included into this interval.
   * @param conversionStrategy if `NoConversion` the `DataPoint`'s value type will the actual one, as retrieved from the storage,
   *                           otherwise, if `ConvertToDouble` the value will be converted to Double
   * @return the `RDD`
   */
  @SuppressWarnings(Array("org.wartremover.warts.Nothing", "org.wartremover.warts.Throw"))
  def load(
    metricName: String,
    tags: Map[String, String] = Map.empty[String, String],
    interval: Option[(Long, Long)] = None,
    conversionStrategy: ConversionStrategy = NoConversion
  ): RDD[DataPoint[_ <: AnyVal]] = {

    log.trace("Loading metric and tags uids")

    val uidScan = getUIDScan(metricName, tags)
    val tsdbUID = sparkSession.loadTable(tsdbUidTable, uidScan)
    val metricsUID: Array[Array[Byte]] = tsdbUID.map(p => p._2.getValue("id".getBytes, "metrics".getBytes())).filter(_ != null).collect
    val (tagKUIDs, tagVUIDs) = if (tags.isEmpty)
      (Map.empty[String, Array[Byte]], Map.empty[String, Array[Byte]])
    else {
      (
        tsdbUID.map(p => (new String(p._1.copyBytes), p._2.getValue("id".getBytes, "tagk".getBytes))).filter(_._2 != null).collect.toMap,
        tsdbUID.map(p => (new String(p._1.copyBytes), p._2.getValue("id".getBytes, "tagv".getBytes))).filter(_._2 != null).collect.toMap
      )
    }
    if (metricsUID.length == 0)
      throw new Exception(s"Metric not found: $metricName")
    log.trace("Loading metric and tags uids: done")

    val rows = if (saltWidth == 0) {
      log.trace("computing hbase rows without salting")
      val metricScan = getMetricScan(
        -1: Byte,
        tags,
        metricsUID.last,
        tagKUIDs,
        tagVUIDs,
        interval
      )
      sparkSession.loadTable(tsdbTable, metricScan)
    } else {
      assert(saltWidth == 1)
      assert(saltBuckets >= 1)
      log.trace("computing hbase rows with salting")
      val rdds = (0 until saltBuckets) map {
        bucket =>
          val metricScan = getMetricScan(
            bucket.toByte,
            tags,
            metricsUID.last,
            tagKUIDs,
            tagVUIDs,
            interval
          )
          sparkSession.loadTable(tsdbTable, metricScan)
      } toList

      val initRDD = rdds.headOption.getOrElse(throw new Exception("There must be at least one RDD"))
      val otherRDDs = rdds.drop(1)

      if (otherRDDs.isEmpty)
        initRDD
      else
        otherRDDs.fold(initRDD)((rdd1, rdd2) => rdd1.union(rdd2))
    }

    val rdd = rows.mapPartitions[Iterator[DataPoint[_ <: AnyVal]]](f = iterator => {
      TSDBClientManager.init(
        keytabLocalTempDir = keytabLocalTempDir_,
        keytabData = keytabData_,
        principal = principal_,
        baseConf = hbaseConfiguration,
        tsdbTable = tsdbTable,
        tsdbUidTable = tsdbUidTable,
        autoCreateMetrics = autoCreateMetrics,
        saltWidth = saltWidth,
        saltBuckets = saltBuckets,
        metricWidth = metricWidth,
        tagkWidth = tagkWidth,
        tagvWidth = tagvWidth,
        preloadUidCache = preloadUidCache,
        preloadUidCacheMaxEntries = preloadUidCacheMaxEntries
      )
      new Iterator[Iterator[DataPoint[_ <: AnyVal]]] {

        log.trace("getting the TSDB client instance from the pool")
        val tsdb: TSDB = TSDBClientManager.pool.borrowObject()

        val i: Iterator[Iterator[DataPoint[_ <: AnyVal]]] = iterator.map(row => process(row, tsdb, interval, conversionStrategy))

        override def hasNext: Boolean =
          if (!i.hasNext) {
            log.trace("iterating done, returning the TSDB client instance to the pool")
            TSDBClientManager.pool.returnObject(tsdb)
            false
          } else
            i.hasNext

        override def next(): Iterator[DataPoint[_ <: AnyVal]] = i.next()
      }
    }, preservesPartitioning = true)

    rdd.flatMap(identity[Iterator[DataPoint[_ <: AnyVal]]])
  }

  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements", "org.wartremover.warts.Nothing", "org.wartremover.warts.StringPlusAny", "org.wartremover.warts.Throw"))
  private def process(row: (ImmutableBytesWritable, Result), tsdb: TSDB, interval: Option[(Long, Long)], conversionStrategy: ConversionStrategy): Iterator[DataPoint[_ <: AnyVal]] = {
    log.trace("processing row")
    val key = row._1.get()
    val metric = Internal.metricName(tsdb, key)
    val baseTime = Internal.baseTime(tsdb, key)
    val tags = Internal.getTags(tsdb, key).asScala
    val dps = new ListBuffer[DataPoint[_ <: AnyVal]]
    row._2.rawCells().foreach[Unit](cell => {
      val family = util.Arrays.copyOfRange(cell.getFamilyArray, cell.getFamilyOffset, cell.getFamilyOffset + cell.getFamilyLength)
      val qualifier = util.Arrays.copyOfRange(cell.getQualifierArray, cell.getQualifierOffset, cell.getQualifierOffset + cell.getQualifierLength)
      val value = util.Arrays.copyOfRange(cell.getValueArray, cell.getValueOffset, cell.getValueOffset + cell.getValueLength)
      val kv = new KeyValue(key, family, qualifier, cell.getTimestamp, value)
      if (qualifier.length == 2 || qualifier.length == 4 && Internal.inMilliseconds(qualifier)) {
        val cell = Internal.parseSingleValue(kv)
        if (cell == null) {
          throw new IllegalDataException("Unable to parse row: " + kv)
        }
        val ts = cell.absoluteTimestamp(baseTime)
        val isInTheInterval = interval.fold(true)(
          interval => if (Internal.inMilliseconds(cell.qualifier()))
            ts >= interval._1 * 1000 && ts < interval._2 * 1000
          else
            ts >= interval._1 && ts < interval._2
        )
        if (isInTheInterval)
          dps += (conversionStrategy match {
            case ConvertToDouble => DataPoint(metric, ts, cell.parseValue().doubleValue(), tags.toMap)
            case NoConversion => if (cell.isInteger)
              DataPoint(metric, cell.absoluteTimestamp(baseTime), cell.parseValue().longValue(), tags.toMap)
            else
              DataPoint(metric, cell.absoluteTimestamp(baseTime), cell.parseValue().doubleValue(), tags.toMap)
          })
      } else {
        // compacted column
        log.trace("processing compacted row")
        val cells = new ListBuffer[Internal.Cell]
        try {
          cells ++= Internal.extractDataPoints(kv).asScala
        } catch {
          case e: IllegalDataException =>
            throw new IllegalDataException(Bytes.toStringBinary(key), e)
        }
        cells.foreach[Unit](cell => {
          val ts = cell.absoluteTimestamp(baseTime)
          val isInTheInterval = interval.fold(true)(
            interval => if (Internal.inMilliseconds(cell.qualifier()))
              ts >= interval._1 * 1000 && ts < interval._2 * 1000
            else
              ts >= interval._1 && ts < interval._2
          )
          if (isInTheInterval)
            dps += (conversionStrategy match {
              case ConvertToDouble => DataPoint(metric, ts, cell.parseValue().doubleValue(), tags.toMap)
              case NoConversion => if (cell.isInteger)
                DataPoint(metric, ts, cell.parseValue().longValue(), tags.toMap)
              else
                DataPoint(metric, ts, cell.parseValue().doubleValue(), tags.toMap)
            })
          ()
        })
        log.trace(s"processed ${cells.length} cells")
      }
      ()
    })
    log.trace("processing row: done")
    dps.iterator
  }

  /**
   * It writes a [[RDD]][ [[DataPoint]] ] back to OpenTSDB
   *
   * @param timeseries the [[RDD]] of [[DataPoint]]s to be stored
   * @param writeFunc  the implicit writefunc to be used for a specific value type
   * @tparam T the actual type of the `DataPoint`'s value
   */
  def write[T <: AnyVal](timeseries: RDD[DataPoint[T]])(implicit writeFunc: (Iterator[DataPoint[T]], TSDB) => Unit): Unit = {
    timeseries.foreachPartition(it => {
      TSDBClientManager.init(
        keytabLocalTempDir = keytabLocalTempDir_,
        keytabData = keytabData_,
        principal = principal_,
        baseConf = hbaseConfiguration,
        tsdbTable = tsdbTable,
        tsdbUidTable = tsdbUidTable,
        autoCreateMetrics = autoCreateMetrics,
        saltWidth = saltWidth,
        saltBuckets = saltBuckets,
        metricWidth = metricWidth,
        tagkWidth = tagkWidth,
        tagvWidth = tagvWidth,
        preloadUidCache = preloadUidCache,
        preloadUidCacheMaxEntries = preloadUidCacheMaxEntries
      )
      log.trace("getting the TSDB client instance from the pool")
      val tsdb = TSDBClientManager.pool.borrowObject()
      writeFunc(
        new Iterator[DataPoint[T]] {
          override def hasNext: Boolean =
            if (!it.hasNext) {
              log.trace("iterating done, returning the TSDB client instance to the pool")
              TSDBClientManager.pool.returnObject(tsdb)
              false
            } else
              it.hasNext

          override def next(): DataPoint[T] = it.next()
        }, tsdb
      )
    })
  }

  /**
   * It writes a [[DataFrame]] back to OpenTSDB
   *
   * @param timeseries the data frame to be stored
   * @param writeFunc  the implicit writefunc to be used for a specific value type
   */
  def write(timeseries: DataFrame)(implicit writeFunc: (Iterator[DataPoint[Double]], TSDB) => Unit): Unit = {
    assert(timeseries.schema == StructType(
      Array(
        StructField("timestamp", TimestampType, nullable = false),
        StructField("metric", StringType, nullable = false),
        StructField("value", DoubleType, nullable = false),
        StructField("tags", DataTypes.createMapType(StringType, StringType), nullable = false)
      )
    ))
    timeseries.foreachPartition(it => {
      TSDBClientManager.init(
        keytabLocalTempDir = keytabLocalTempDir_,
        keytabData = keytabData_,
        principal = principal_,
        baseConf = hbaseConfiguration,
        tsdbTable = tsdbTable,
        tsdbUidTable = tsdbUidTable,
        autoCreateMetrics = autoCreateMetrics,
        saltWidth = saltWidth,
        saltBuckets = saltBuckets,
        metricWidth = metricWidth,
        tagkWidth = tagkWidth,
        tagvWidth = tagvWidth,
        preloadUidCache = preloadUidCache,
        preloadUidCacheMaxEntries = preloadUidCacheMaxEntries
      )
      log.trace("getting the TSDB client instance from the pool")
      val tsdb = TSDBClientManager.pool.borrowObject()
      writeFunc(
        v1 = new Iterator[DataPoint[Double]] {
        override def hasNext: Boolean =
          if (!it.hasNext) {
            log.trace("iterating done, returning the TSDB client instance to the pool")
            TSDBClientManager.pool.returnObject(tsdb)
            false
          } else
            it.hasNext

        override def next(): DataPoint[Double] = {
          val row = it.next()
          DataPoint(
            row.getAs[String]("metric"),
            row.getAs[Timestamp]("timestamp").getTime,
            row.getAs[Double]("value"),
            row.getAs[Map[String, String]]("tags")
          )
        }
      }, v2 = tsdb
      )
    })
  }

  /**
   * It writes a [[DStream]][ [[DataPoint]] ] back to OpenTSDB
   *
   * @param dstream   the distributed stream
   * @param writeFunc the implicit writefunc to be used for a specific value type
   * @tparam T the actual type of the [[DataPoint]]'s value
   */
  def streamWrite[T <: AnyVal](dstream: DStream[DataPoint[T]])(implicit writeFunc: (Iterator[DataPoint[T]], TSDB) => Unit): Unit = {
    dstream foreachRDD {
      timeseries =>
        timeseries foreachPartition {
          it =>
            TSDBClientManager.init(
              keytabLocalTempDir = keytabLocalTempDir_,
              keytabData = keytabData_,
              principal = principal_,
              baseConf = hbaseConfiguration,
              tsdbTable = tsdbTable,
              tsdbUidTable = tsdbUidTable,
              autoCreateMetrics = autoCreateMetrics,
              saltWidth = saltWidth,
              saltBuckets = saltBuckets,
              metricWidth = metricWidth,
              tagkWidth = tagkWidth,
              tagvWidth = tagvWidth,
              preloadUidCache = preloadUidCache,
              preloadUidCacheMaxEntries = preloadUidCacheMaxEntries
            )
            log.trace("getting the TSDB client instance from the pool")
            val tsdb = TSDBClientManager.pool.borrowObject()
            writeFunc(
              new Iterator[DataPoint[T]] {
                override def hasNext: Boolean =
                  if (!it.hasNext) {
                    log.trace("iterating done, returning the TSDB client instance to the pool")
                    TSDBClientManager.pool.returnObject(tsdb)
                    false
                  } else
                    it.hasNext

                override def next(): DataPoint[T] = it.next()
              }, tsdb
            )
        }
    }
  }

}
