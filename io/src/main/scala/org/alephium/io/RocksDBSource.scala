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
import java.nio.file.Path

import org.rocksdb._
import org.rocksdb.util.SizeUnit

import org.alephium.util.AVector

object RocksDBSource {
  import IOUtils.tryExecute

  {
    RocksDB.loadLibrary()
  }

  sealed abstract class ColumnFamily(val name: String)

  object ColumnFamily {

    case object All       extends ColumnFamily("all")
    case object Block     extends ColumnFamily("block")
    case object Broker    extends ColumnFamily("broker")
    case object Header    extends ColumnFamily("header")
    case object PendingTx extends ColumnFamily("pendingtx")
    case object ReadyTx   extends ColumnFamily("readytx")
    case object Trie      extends ColumnFamily("trie")
    case object Log       extends ColumnFamily("log")

    val values: AVector[ColumnFamily] =
      AVector(All, Block, Broker, Header, Log, PendingTx, ReadyTx, Trie)
  }

  final case class Compaction(
      initialFileSize: Long,
      blockSize: Long,
      writeRateLimit: Option[Long]
  )

  object Compaction {
    import SizeUnit._

    val SSD: Compaction = Compaction(
      initialFileSize = 64 * MB,
      blockSize = 16 * KB,
      writeRateLimit = None
    )

    val HDD: Compaction = Compaction(
      initialFileSize = 256 * MB,
      blockSize = 64 * KB,
      writeRateLimit = Some(16 * MB)
    )
  }

  object Settings {
    RocksDB.loadLibrary()

    // TODO All options should become part of configuration
    val MaxOpenFiles: Int           = 512
    val BytesPerSync: Long          = 1 * SizeUnit.MB
    val MemoryBudget: Long          = 128 * SizeUnit.MB
    val WriteBufferMemoryRatio: Int = 2
    val BlockCacheMemoryRatio: Int  = 3
    val CPURatio: Int               = 2

    val readOptions: ReadOptions   = (new ReadOptions).setVerifyChecksums(false)
    val writeOptions: WriteOptions = new WriteOptions
    val syncWrite: WriteOptions    = (new WriteOptions).setSync(true)

    val columns: Int             = ColumnFamily.values.length
    val memoryBudgetPerCol: Long = MemoryBudget / columns

    def databaseOptions(compaction: Compaction): DBOptions =
      databaseOptionsForBudget(compaction, memoryBudgetPerCol)

    def databaseOptionsForBudget(compaction: Compaction, memoryBudgetPerCol: Long): DBOptions = {
      val options = new DBOptions()
        .setUseFsync(false)
        .setCreateIfMissing(true)
        .setCreateMissingColumnFamilies(true)
        .setMaxOpenFiles(MaxOpenFiles)
        .setKeepLogFileNum(1)
        .setBytesPerSync(BytesPerSync)
        .setDbWriteBufferSize(memoryBudgetPerCol / WriteBufferMemoryRatio)
        .setIncreaseParallelism(Math.max(1, Runtime.getRuntime.availableProcessors() / CPURatio))

      compaction.writeRateLimit match {
        case Some(rateLimit) => options.setRateLimiter(new RateLimiter(rateLimit))
        case None            => options
      }
    }

    def columnOptions(compaction: Compaction): ColumnFamilyOptions =
      columnOptionsForBudget(compaction, memoryBudgetPerCol)

    def columnOptionsForBudget(
        compaction: Compaction,
        memoryBudgetPerCol: Long
    ): ColumnFamilyOptions = {
      import scala.jdk.CollectionConverters._

      (new ColumnFamilyOptions)
        .setLevelCompactionDynamicLevelBytes(true)
        .setTableFormatConfig(
          new BlockBasedTableConfig()
            .setBlockSize(compaction.blockSize)
            .setBlockCache(new LRUCache(MemoryBudget / BlockCacheMemoryRatio))
            .setCacheIndexAndFilterBlocks(true)
            .setPinL0FilterAndIndexBlocksInCache(true)
        )
        .optimizeLevelStyleCompaction(memoryBudgetPerCol)
        .setTargetFileSizeBase(compaction.initialFileSize)
        .setCompressionPerLevel(Nil.asJava)
    }
  }

  def createUnsafe(rootPath: Path, dbFolder: String, dbName: String): RocksDBSource = {
    val path = {
      val path = rootPath.resolve(dbFolder)
      IOUtils.createDirUnsafe(path)
      path
    }
    val dbPath = path.resolve(dbName)
    RocksDBSource.openUnsafe(dbPath, RocksDBSource.Compaction.HDD)
  }

  def open(path: Path, compaction: Compaction): IOResult[RocksDBSource] =
    tryExecute {
      openUnsafe(path, compaction)
    }

  def openUnsafe(path: Path, compaction: Compaction): RocksDBSource =
    openUnsafeWithOptions(
      path,
      Settings.databaseOptions(compaction),
      Settings.columnOptions(compaction)
    )

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def openUnsafeWithOptions(
      path: Path,
      databaseOptions: DBOptions,
      columnOptions: ColumnFamilyOptions
  ): RocksDBSource = {
    import scala.jdk.CollectionConverters._

    val handles = new scala.collection.mutable.ArrayBuffer[ColumnFamilyHandle]()
    val descriptors = (ColumnFamily.values.map(_.name) :+ "default").map { name =>
      new ColumnFamilyDescriptor(name.getBytes(StandardCharsets.UTF_8), columnOptions)
    }

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
    extends KeyValueSource {
  import IOUtils.tryExecute
  import RocksDBSource._

  def handle(cf: ColumnFamily): ColumnFamilyHandle =
    cfHandles(ColumnFamily.values.indexWhere(_ == cf))

  def close(): IOResult[Unit] =
    tryExecute {
      db.close()
    }

  def closeUnsafe(): Unit = {
    cfHandles.foreach(_.close())
    db.close()
  }

  def dESTROY(): IOResult[Unit] =
    tryExecute {
      dESTROYUnsafe()
    }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def dESTROYUnsafe(): Unit = {
    closeUnsafe()
    RocksDB.destroyDB(path.toString, new Options())
  }
}
