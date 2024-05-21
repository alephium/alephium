// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.io

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import com.typesafe.scalalogging.LazyLogging
import org.rocksdb._
import org.rocksdb.util.{SizeUnit, StdErrLogger}

import org.alephium.util.{AVector, Env => AEnv}

object RocksDBSource {
  import IOUtils.tryExecute

  {
    RocksDB.loadLibrary()
  }

  sealed abstract class ColumnFamily(val name: String)

  object ColumnFamily {

    case object All        extends ColumnFamily("all")
    case object Block      extends ColumnFamily("block")
    case object Broker     extends ColumnFamily("broker")
    case object Header     extends ColumnFamily("header")
    case object PendingTx  extends ColumnFamily("pendingtx")
    case object ReadyTx    extends ColumnFamily("readytx")
    case object Trie       extends ColumnFamily("trie")
    case object Log        extends ColumnFamily("log")
    case object LogCounter extends ColumnFamily("logcounter")

    val values: AVector[ColumnFamily] =
      AVector(All, Block, Broker, Header, Log, LogCounter, PendingTx, ReadyTx, Trie)
  }

  // scalastyle:off magic.number
  object TestSettings {
    RocksDB.loadLibrary()

    val readOptions: ReadOptions   = (new ReadOptions).setVerifyChecksums(false)
    val writeOptions: WriteOptions = new WriteOptions
    val syncWrite: WriteOptions    = (new WriteOptions).setSync(true)

    def databaseOptions(): DBOptions = {
      new DBOptions()
        .setUseFsync(false)
        .setCreateIfMissing(true)
        .setCreateMissingColumnFamilies(true)
        .setMaxOpenFiles(512)
        .setKeepLogFileNum(1)
        .setBytesPerSync(1 * SizeUnit.MB)
        .setDbWriteBufferSize(8 * SizeUnit.MB)
        .setIncreaseParallelism(Math.max(1, Runtime.getRuntime.availableProcessors() / 2))
        .setRateLimiter(new RateLimiter(16 * SizeUnit.MB))
    }

    def columnOptions(): ColumnFamilyOptions = {
      import scala.jdk.CollectionConverters._

      (new ColumnFamilyOptions)
        .setLevelCompactionDynamicLevelBytes(true)
        .setTableFormatConfig(
          new BlockBasedTableConfig()
            .setBlockSize(64 * SizeUnit.KB)
            .setBlockCache(new LRUCache(5 * SizeUnit.MB))
            .setCacheIndexAndFilterBlocks(true)
            .setPinL0FilterAndIndexBlocksInCache(true)
        )
        .optimizeLevelStyleCompaction(8 * SizeUnit.MB)
        .setTargetFileSizeBase(256 * SizeUnit.MB)
        .setCompressionPerLevel(Nil.asJava)
    }
  }

  object ProdSettings {
    RocksDB.loadLibrary()

    val readOptions: ReadOptions = (new ReadOptions).setVerifyChecksums(false)
    val writeOptions: WriteOptions =
      (new WriteOptions).setNoSlowdown(true).setIgnoreMissingColumnFamilies(true)
    val syncWrite: WriteOptions = (new WriteOptions).setNoSlowdown(true).setSync(true)

    def databaseOptions(): DBOptions = {
      new DBOptions()
        .setCreateIfMissing(true)
        .setCreateMissingColumnFamilies(true)
        .setMaxOpenFiles(1024)
        .setLogFileTimeToRoll(86_400L) // one day
        .setKeepLogFileNum(7)
        .setDbWriteBufferSize(256 * SizeUnit.MB)
        .setLogger(new StdErrLogger(InfoLogLevel.ERROR_LEVEL))
        .setEnv(Env.getDefault().setBackgroundThreads(6))
        .setMaxTotalWalSize(1_073_741_824L) // 1GB
        .setRecycleLogFileNum(16)
    }

    def columnOptions(ioHeavy: Boolean): ColumnFamilyOptions = {
      (new ColumnFamilyOptions)
        .setLevelCompactionDynamicLevelBytes(true)
        .setTtl(0)
        .setCompressionType(CompressionType.LZ4_COMPRESSION)
        .setTableFormatConfig(
          new BlockBasedTableConfig()
            .setFormatVersion(5) // Since RocksDB 6.6
            .setBlockCache(new LRUCache(32 * SizeUnit.MB))
            .setFilterPolicy(new BloomFilter(10, false))
            .setPartitionFilters(true)
            .setCacheIndexAndFilterBlocks(false)
            .setBlockSize(32 * SizeUnit.KB)
        )
        .setWriteBufferSize(if (ioHeavy) 128 * SizeUnit.MB else 64 * SizeUnit.MB)
    }
  }

  def createUnsafe(rootPath: Path, dbFolder: String, dbName: String): RocksDBSource = {
    val path = {
      val path = rootPath.resolve(dbFolder)
      IOUtils.createDirUnsafe(path)
      path
    }
    val dbPath = path.resolve(dbName)
    RocksDBSource.openUnsafe(dbPath)
  }

  def open(path: Path): IOResult[RocksDBSource] =
    tryExecute {
      openUnsafe(path)
    }

  def openUnsafe(path: Path): RocksDBSource = {
    val (databaseOptions, columnOptions) = AEnv.resolve() match {
      case AEnv.Test =>
        (TestSettings.databaseOptions(), (_: Boolean) => TestSettings.columnOptions())
      case _ => (ProdSettings.databaseOptions(), ProdSettings.columnOptions)
    }
    openUnsafeWithOptions(path, databaseOptions, columnOptions)
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  private def openUnsafeWithOptions(
      path: Path,
      databaseOptions: DBOptions,
      columnOptions: (Boolean) => ColumnFamilyOptions
  ): RocksDBSource = {
    import scala.jdk.CollectionConverters._

    val handles = new scala.collection.mutable.ArrayBuffer[ColumnFamilyHandle]()
    val descriptors = (ColumnFamily.values.map(_.name) :+ "default").map { name =>
      new ColumnFamilyDescriptor(
        name.getBytes(StandardCharsets.UTF_8),
        columnOptions(name == ColumnFamily.Trie.name)
      )
    }

    if (!Files.exists(path)) path.toFile.mkdir()

    val db = RocksDB.open(
      databaseOptions,
      path.toString,
      descriptors.toIterable.toList.asJava,
      handles.asJava
    )

    new RocksDBSource(path, db, AVector.from(handles))
  }
}

class RocksDBSource(val path: Path, val db: RocksDB, val cfHandles: AVector[ColumnFamilyHandle])
    extends KeyValueSource
    with LazyLogging {
  import IOUtils.tryExecute
  import RocksDBSource._

  def handle(cf: ColumnFamily): ColumnFamilyHandle =
    cfHandles(ColumnFamily.values.indexWhere(_ == cf))

  def close(): IOResult[Unit] =
    tryExecute {
      closeUnsafe()
    }

  def closeUnsafe(): Unit = {
    logger.info("Closing RocksDB")
    // fsync the WAL files, as suggested by the doc of RocksDB.close()
    db.write(new WriteOptions().setSync(true), new WriteBatch())
    db.flushWal(true)
    cfHandles.foreach(_.close())
    db.close()
  }

  def dESTROY(): IOResult[Unit] =
    tryExecute {
      dESTROYUnsafe()
    }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def dESTROYUnsafe(): Unit = {
    cfHandles.foreach(_.close())
    db.close()
    RocksDB.destroyDB(path.toString, new Options())
  }
}
