package org.alephium.flow.io

import java.nio.file.Path

import org.rocksdb._
import org.rocksdb.util.SizeUnit

import org.alephium.util.AVector

object RocksDBStorage {
  import IOUtils.execute

  {
    RocksDB.loadLibrary()
  }

  sealed abstract class ColumnFamily(val name: String)

  object ColumnFamily {

    case object All  extends ColumnFamily("all")
    case object Trie extends ColumnFamily("trie")

    val values: AVector[ColumnFamily] = AVector(All, Trie)
  }

  case class Compaction(
      initialFileSize: Long,
      blockSize: Long,
      writeRateLimit: Option[Long]
  )

  object Compaction {
    import SizeUnit._

    val SSD = Compaction(
      initialFileSize = 64 * MB,
      blockSize       = 16 * KB,
      writeRateLimit  = None
    )

    val HDD = Compaction(
      initialFileSize = 256 * MB,
      blockSize       = 64 * KB,
      writeRateLimit  = Some(16 * MB)
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

    def columnOptionsForBudget(compaction: Compaction,
                               memoryBudgetPerCol: Long): ColumnFamilyOptions = {
      import scala.collection.JavaConverters._

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

  def open(path: Path, compaction: Compaction): IOResult[RocksDBStorage] = execute {
    openUnsafe(path, compaction)
  }

  def openUnsafe(path: Path, compaction: Compaction): RocksDBStorage =
    openUnsafeWithOptions(path,
                          Settings.databaseOptions(compaction),
                          Settings.columnOptions(compaction))

  def openUnsafeWithOptions(path: Path,
                            databaseOptions: DBOptions,
                            columnOptions: ColumnFamilyOptions): RocksDBStorage = {
    import scala.collection.JavaConverters._

    val handles = new scala.collection.mutable.ArrayBuffer[ColumnFamilyHandle]()
    val descriptors = (ColumnFamily.values.map(_.name) :+ "default").map { name =>
      new ColumnFamilyDescriptor(name.getBytes, columnOptions)
    }

    val db =
      RocksDB.open(databaseOptions,
                   path.toString,
                   descriptors.toIterable.toList.asJava,
                   handles.asJava)

    new RocksDBStorage(path, db, AVector.fromIterator(handles.toIterator))
  }

  def dESTROY(path: Path): IOResult[Unit] = execute {
    RocksDB.destroyDB(path.toString, new Options())
  }

  def dESTROY(db: RocksDBStorage): IOResult[Unit] = execute {
    dESTROYUnsafe(db)
  }

  def dESTROYUnsafe(db: RocksDBStorage): Unit = {
    RocksDB.destroyDB(db.path.toString, new Options())
  }
}

class RocksDBStorage(val path: Path, val db: RocksDB, cfHandles: AVector[ColumnFamilyHandle]) {
  import RocksDBStorage._
  import IOUtils.execute

  def handle(cf: ColumnFamily): ColumnFamilyHandle =
    cfHandles(ColumnFamily.values.indexWhere(_ == cf))

  def close(): IOResult[Unit] = execute {
    db.close()
  }

  def closeUnsafe(): Unit = db.close()
}
