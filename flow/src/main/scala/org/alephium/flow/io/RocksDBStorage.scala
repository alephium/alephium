package org.alephium.flow.io

import java.nio.file.Path

import akka.util.ByteString
import org.alephium.serde._
import org.rocksdb.{BlockBasedTableConfig, LRUCache, Options, RateLimiter, ReadOptions, RocksDB}
import org.rocksdb.util.SizeUnit

object RocksDBStorage {
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
    val MaxOpenFiles: Int  = 512
    val BytesPerSync: Long = 1048576
    val MemoryBudget: Long = 128 * SizeUnit.MB

    def default(compaction: Compaction, columns: Int): Options = {
      val memoryBudgetPerCol = MemoryBudget / columns

      val options = new Options()
        .setUseFsync(false)
        .setCreateIfMissing(true)
        .setMaxOpenFiles(MaxOpenFiles)
        .setKeepLogFileNum(1)
        .setBytesPerSync(BytesPerSync)
        .setDbWriteBufferSize(memoryBudgetPerCol / 2)
        .setIncreaseParallelism(Math.max(1, Runtime.getRuntime().availableProcessors()))
        .setTargetFileSizeBase(compaction.initialFileSize)
        .setTableFormatConfig(
          new BlockBasedTableConfig()
            .setBlockCacheSize(compaction.blockSize)
            .setBlockCache(new LRUCache(MemoryBudget / 3))
        )

      compaction.writeRateLimit match {
        case Some(rateLimit) => options.setRateLimiter(new RateLimiter(rateLimit))
        case None            => options
      }
    }
  }

  val readOptions = (new ReadOptions).setVerifyChecksums(false)

  def open(path: Path, options: Options): IOResult[RocksDBStorage] = execute {
    openUnsafe(path, options)
  }

  def openUnsafe(path: Path, options: Options): RocksDBStorage = {
    RocksDB.loadLibrary()
    val db = RocksDB.open(options, path.toString)
    new RocksDBStorage(path, db, readOptions)
  }

  def dESTROY(path: Path, options: Options = new Options()): IOResult[Unit] = execute {
    RocksDB.destroyDB(path.toString, options)
  }

  def dESTROY(db: HeaderDB): IOResult[Unit] = execute {
    dESTROYUnsafe(db)
  }

  def dESTROYUnsafe(db: HeaderDB): Unit = {
    RocksDB.destroyDB(db.path.toString, new Options())
  }

  @inline
  def execute[T](f: => T): IOResult[T] = {
    try Right(f)
    catch {
      case e: Throwable => Left(IOError(e))
    }
  }
}

class RocksDBStorage(val path: Path, db: RocksDB, readOptions: ReadOptions)
    extends KeyValueStorage {
  import RocksDBStorage._

  def close(): IOResult[Unit] = execute {
    db.close()
  }

  def closeUnsafe(): Unit = db.close()

  def getRaw(key: ByteString): IOResult[ByteString] = execute {
    getRawUnsafe(key)
  }

  def getRawUnsafe(key: ByteString): ByteString = {
    val result = db.get(readOptions, key.toArray)
    if (result == null) throw IOError.RocksDB.keyNotFound.e
    else ByteString.fromArrayUnsafe(result)
  }

  def get[V: Serde](key: ByteString): IOResult[V] = execute {
    getUnsafe[V](key)
  }

  def getUnsafe[V: Serde](key: ByteString): V = {
    val data = getRawUnsafe(key)
    deserialize[V](data) match {
      case Left(e)  => throw e
      case Right(v) => v
    }
  }

  def getOptRaw(key: ByteString): IOResult[Option[ByteString]] = execute {
    getOptRawUnsafe(key)
  }

  def getOptRawUnsafe(key: ByteString): Option[ByteString] = {
    val result = db.get(key.toArray)
    if (result == null) None
    else {
      Some(ByteString.fromArrayUnsafe(result))
    }
  }

  def getOpt[V: Serde](key: ByteString): IOResult[Option[V]] = execute {
    getOptUnsafe[V](key)
  }

  def getOptUnsafe[V: Serde](key: ByteString): Option[V] = {
    getOptRawUnsafe(key) map { data =>
      deserialize[V](data) match {
        case Left(e)  => throw e
        case Right(v) => v
      }
    }
  }

  def exists(key: ByteString): IOResult[Boolean] = execute {
    existsUnsafe(key)
  }

  def existsUnsafe(key: ByteString): Boolean = {
    val result = db.get(key.toArray)
    result != null
  }

  def putRaw(key: ByteString, value: ByteString): IOResult[Unit] = execute {
    putRawUnsafe(key, value)
  }

  def putRawUnsafe(key: ByteString, value: ByteString): Unit = {
    db.put(key.toArray, value.toArray)
  }

  def put[V: Serde](key: ByteString, value: V): IOResult[Unit] = {
    putRaw(key, serialize(value))
  }

  def putUnsafe[V: Serde](key: ByteString, value: V): Unit = {
    putRawUnsafe(key, serialize(value))
  }

  // TODO: should we check the existence of the key?
  def delete(key: ByteString): IOResult[Unit] = execute {
    deleteUnsafe(key)
  }

  // TODO: should we check the existence of the key?
  def deleteUnsafe(key: ByteString): Unit = {
    db.delete(key.toArray)
  }

}
