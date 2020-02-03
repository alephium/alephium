package org.alephium.flow.io

import akka.util.ByteString
import org.rocksdb.{ReadOptions, WriteOptions}

import org.alephium.serde._

object RocksDBColumn {
  import RocksDBStorage.Settings

  def apply(storage: RocksDBStorage,
            cf: RocksDBStorage.ColumnFamily,
            writeOptions: WriteOptions = Settings.writeOptions,
            readOptions: ReadOptions   = Settings.readOptions): RocksDBColumn =
    new RocksDBColumn(storage, cf, writeOptions, readOptions)
}

class RocksDBColumn(
    storage: RocksDBStorage,
    cf: RocksDBStorage.ColumnFamily,
    writeOptions: WriteOptions,
    readOptions: ReadOptions
) extends KeyValueStorage {
  import IOUtils.execute

  val handle = storage.handle(cf)

  def getRaw(key: ByteString): IOResult[ByteString] = execute {
    getRawUnsafe(key)
  }

  def getRawUnsafe(key: ByteString): ByteString = {
    val result = storage.db.get(handle, readOptions, key.toArray)
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
    val result = storage.db.get(handle, key.toArray)
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
    val result = storage.db.get(handle, key.toArray)
    result != null
  }

  def putRaw(key: ByteString, value: ByteString): IOResult[Unit] = execute {
    putRawUnsafe(key, value)
  }

  def putRawUnsafe(key: ByteString, value: ByteString): Unit = {
    storage.db.put(handle, writeOptions, key.toArray, value.toArray)
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
    storage.db.delete(handle, key.toArray)
  }
}
