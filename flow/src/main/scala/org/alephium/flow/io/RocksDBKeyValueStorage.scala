package org.alephium.flow.io

import org.rocksdb._

import org.alephium.serde._

object RocksDBKeyValueStorage {
  import RocksDBSource.Settings

  def apply[K: Serializer, V: Serde](storage: RocksDBSource,
                                     cf: RocksDBSource.ColumnFamily): KeyValueStorage[K, V] =
    apply(storage, cf, Settings.writeOptions, Settings.readOptions)

  def apply[K: Serializer, V: Serde](storage: RocksDBSource,
                                     cf: RocksDBSource.ColumnFamily,
                                     writeOptions: WriteOptions): KeyValueStorage[K, V] =
    apply(storage, cf, writeOptions, Settings.readOptions)

  def apply[K: Serializer, V: Serde](storage: RocksDBSource,
                                     cf: RocksDBSource.ColumnFamily,
                                     writeOptions: WriteOptions,
                                     readOptions: ReadOptions): KeyValueStorage[K, V] =
    new RocksDBKeyValueStorage(storage, cf, writeOptions, readOptions)
}

class RocksDBKeyValueStorage[K: Serializer, V: Serde](
    storage: RocksDBSource,
    cf: RocksDBSource.ColumnFamily,
    val writeOptions: WriteOptions,
    val readOptions: ReadOptions
) extends KeyValueStorage[K, V]
    with RocksDBColumn {
  import IOUtils.tryExecute

  protected val db: RocksDB                = storage.db
  protected val handle: ColumnFamilyHandle = storage.handle(cf)

  override def get(key: K): IOResult[V] = tryExecute {
    getUnsafe(key)
  }

  override def getUnsafe(key: K): V = {
    val data = getRawUnsafe(serialize(key))
    deserialize[V](data) match {
      case Left(e)  => throw e
      case Right(v) => v
    }
  }

  override def getOpt(key: K): IOResult[Option[V]] = tryExecute {
    getOptUnsafe(key)
  }

  override def getOptUnsafe(key: K): Option[V] = {
    getOptRawUnsafe(serialize(key)) map { data =>
      deserialize[V](data) match {
        case Left(e)  => throw e
        case Right(v) => v
      }
    }
  }

  override def exists(key: K): IOResult[Boolean] = tryExecute {
    existsUnsafe(key)
  }

  override def existsUnsafe(key: K): Boolean = {
    val result = storage.db.get(handle, serialize(key).toArray)
    result != null
  }

  override def put(key: K, value: V): IOResult[Unit] = tryExecute {
    putUnsafe(key, value)
  }

  override def putUnsafe(key: K, value: V): Unit = {
    putRawUnsafe(serialize(key), serialize(value))
  }

  override def delete(key: K): IOResult[Unit] = tryExecute {
    deleteUnsafe(key)
  }

  override def deleteUnsafe(key: K): Unit = {
    deleteRawUnsafe(serialize(key))
  }
}
