package org.alephium.flow.io

import akka.util.ByteString
import org.rocksdb.{ColumnFamilyHandle, ReadOptions, RocksDB, WriteOptions}

object RocksDBColumn {
  import RocksDBStorage.Settings

  def apply(storage: RocksDBStorage, cf: RocksDBStorage.ColumnFamily): RocksDBColumn =
    apply(storage, cf, Settings.writeOptions, Settings.readOptions)

  def apply(storage: RocksDBStorage,
            cf: RocksDBStorage.ColumnFamily,
            writeOptions: WriteOptions): RocksDBColumn =
    apply(storage, cf, writeOptions, Settings.readOptions)

  def apply(storage: RocksDBStorage,
            cf: RocksDBStorage.ColumnFamily,
            _writeOptions: WriteOptions,
            _readOptions: ReadOptions): RocksDBColumn =
    new RocksDBColumn {
      protected def db: RocksDB                = storage.db
      protected def handle: ColumnFamilyHandle = storage.handle(cf)
      protected def writeOptions: WriteOptions = _writeOptions
      protected def readOptions: ReadOptions   = _readOptions
    }
}

trait RocksDBColumn {
  protected def db: RocksDB
  protected def handle: ColumnFamilyHandle
  protected def writeOptions: WriteOptions
  protected def readOptions: ReadOptions

  def getRawUnsafe(key: ByteString): ByteString = {
    val result = db.get(handle, readOptions, key.toArray)
    if (result == null) throw IOError.RocksDB.keyNotFound.e
    else ByteString.fromArrayUnsafe(result)
  }

  def getOptRawUnsafe(key: ByteString): Option[ByteString] = {
    val result = db.get(handle, key.toArray)
    if (result == null) None
    else {
      Some(ByteString.fromArrayUnsafe(result))
    }
  }

  def putRawUnsafe(key: ByteString, value: ByteString): Unit = {
    db.put(handle, writeOptions, key.toArray, value.toArray)
  }

  def existsRawUnsafe(key: ByteString): Boolean = {
    val result = db.get(handle, key.toArray)
    result != null
  }

  def deleteRawUnsafe(key: ByteString): Unit = {
    db.delete(handle, key.toArray)
  }
}
