/*
 * Copyright 2016 sadikovi
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

package com.github.sadikovi.spark.rdd

import java.io.IOException

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, FSDataInputStream, Path}
import org.apache.spark.{SparkContext, Partition, TaskContext, InterruptibleIterator}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Row => SQLRow}
import org.apache.spark.sql.sources._

import com.github.sadikovi.netflowlib.NetFlowReader
import com.github.sadikovi.netflowlib.predicate.Operators.FilterPredicate
import com.github.sadikovi.spark.netflow.sources._

/**
 * NetFlow metadata that describes file to process. Contains expected version of a file, absolute
 * resolved path to the file, and it's length, buffer size for a particular file (currently we
 * do not make that distinction).
 */
private[spark] case class NetFlowMetadata(
  version: Short,
  path: String,
  length: Long,
  bufferSize: Int
)

/** NetFlowFilePartition to hold sequence of file paths */
private[spark] class NetFlowFilePartition[T<:NetFlowMetadata: ClassTag] (
    var rddId: Long,
    var slice: Int,
    var values: Seq[T]) extends Partition with Serializable {
  def iterator: Iterator[T] = values.iterator

  override def hashCode(): Int = (41 * (41 + rddId) + slice).toInt

  override def equals(other: Any): Boolean = other match {
    case that: NetFlowFilePartition[_] => this.rddId == that.rddId && this.slice == that.slice
    case _ => false
  }

  override def index: Int = slice
}

/**
 * `NetFlowFileRDD` is designed to process NetFlow file of specific version and return iterator of
 * SQL rows back. Used internally solely for the purpose of datasource API. We assume that we
 * process files of the same version, and prune common fields. `NetFlowFileRDD` operates on already
 * resolved columns, the same applies to filters.
 */
private[spark] class NetFlowFileRDD[T<:SQLRow: ClassTag] (
    @transient sc: SparkContext,
    @transient data: Seq[NetFlowMetadata],
    private val numSlices: Int,
    val applyConversion: Boolean,
    val resolvedColumns: Array[MappedColumn],
    val resolvedFilter: Option[FilterPredicate]) extends FileRDD[SQLRow](sc, Nil) {
  /** Partition [[NetFlowMetadata]], slightly modified Spark partitioning function */
  private def slice(seq: Seq[NetFlowMetadata], numSlices: Int): Seq[Seq[NetFlowMetadata]] = {
    require(numSlices >= 1, "Positive number of slices required")

    def positions(length: Long, numSlices: Int): Iterator[(Int, Int)] = {
      (0 until numSlices).iterator.map(i => {
        val start = ((i * length) / numSlices).toInt
        val end = (((i + 1) * length) / numSlices).toInt
        (start, end)
      })
    }

    val array = seq.toArray
    positions(array.length, numSlices).map { case (start, end) =>
      array.slice(start, end).toSeq
    }.toSeq
  }

  override def getPartitions: Array[Partition] = {
    val slices = this.slice(data, numSlices).toArray
    slices.indices.map(i => new NetFlowFilePartition[NetFlowMetadata](id, i, slices(i))).toArray
  }

  override def compute(s: Partition, context: TaskContext): Iterator[SQLRow] = {
    // Hadoop configuration
    val conf = getConf()
    // Number of columns to process
    val numColumns = resolvedColumns.length
    // Array of internal columns for library
    val internalColumns = resolvedColumns.map(_.internalColumn)
    // Total buffer of records
    var buffer: Iterator[Array[Object]] = Iterator.empty

    for (elem <- s.asInstanceOf[NetFlowFilePartition[NetFlowMetadata]].iterator) {
      // Reconstruct file status: file path, length in bytes
      val path = new Path(elem.path)
      val fs = path.getFileSystem(conf)
      val fileLength = elem.length

      // Prepare file stream
      val stm: FSDataInputStream = fs.open(path)
      val reader = NetFlowReader.prepareReader(stm, elem.bufferSize)
      val header = reader.getHeader()
      // Actual version of the file
      val actualVersion = header.getFlowVersion()
      // Compression flag
      val isCompressed = header.isCompressed()

      logInfo(s"""
          > NetFlow: {
          >   File: ${elem.path},
          >   File length: ${fileLength} bytes,
          >   Flow version: ${actualVersion},
          >   Compression: ${isCompressed},
          >   Buffer size: ${elem.bufferSize} bytes,
          >   Start capture: ${header.getStartCapture()},
          >   End capture: ${header.getEndCapture()},
          >   Hostname: ${header.getHostname()},
          >   Comments: ${header.getComments()}
          > }
        """.stripMargin('>'))

      // Currently we cannot resolve version and proceed with parsing, we require pre-set version.
      require(actualVersion == elem.version,
        s"Expected version ${elem.version}, got ${actualVersion} for file ${elem.path}. " +
          "Scan of the files with different (compatible) versions, e.g. 5, 6, and 7 is not " +
          "supported currently")

      // Build record buffer based on resolved filter, if filter is not defined use default scan
      // with trivial predicate
      val recordBuffer = if (resolvedFilter.nonEmpty) {
        reader.prepareRecordBuffer(internalColumns, resolvedFilter.get)
      } else {
        reader.prepareRecordBuffer(internalColumns)
      }

      val rawIterator = recordBuffer.iterator().asScala

      // Conversion iterator, applies defined modification for convertable fields
      val conversionsIterator = if (applyConversion) {
        // For each field we check if possible conversion is available. If it is we apply direct
        // conversion, otherwise return unchanged value
        rawIterator.map(arr => {
          for (i <- 0 until numColumns) {
            resolvedColumns(i).convertFunction match {
              case Some(func) => arr(i) = func.direct(arr(i))
              case None => // do nothing
            }
          }
          arr
        })
      } else {
        rawIterator
      }

      buffer = buffer ++ conversionsIterator
    }

    new Iterator[SQLRow] {
      def next(): SQLRow = {
        SQLRow.fromSeq(buffer.next())
      }

      def hasNext: Boolean = {
        buffer.hasNext
      }
    }
  }
}
