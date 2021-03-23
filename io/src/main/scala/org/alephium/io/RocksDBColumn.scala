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

import akka.util.ByteString
import org.rocksdb.{ColumnFamilyHandle, ReadOptions, RocksDB, WriteOptions}

object RocksDBColumn {
  import RocksDBSource.Settings

  def apply(storage: RocksDBSource, cf: RocksDBSource.ColumnFamily): RocksDBColumn =
    apply(storage, cf, Settings.writeOptions, Settings.readOptions)

  def apply(
      storage: RocksDBSource,
      cf: RocksDBSource.ColumnFamily,
      writeOptions: WriteOptions
  ): RocksDBColumn =
    apply(storage, cf, writeOptions, Settings.readOptions)

  def apply(
      storage: RocksDBSource,
      cf: RocksDBSource.ColumnFamily,
      _writeOptions: WriteOptions,
      _readOptions: ReadOptions
  ): RocksDBColumn =
    new RocksDBColumn {
      protected def db: RocksDB                = storage.db
      protected def handle: ColumnFamilyHandle = storage.handle(cf)
      protected def writeOptions: WriteOptions = _writeOptions
      protected def readOptions: ReadOptions   = _readOptions
    }
}

trait RocksDBColumn extends RawKeyValueStorage {
  protected def db: RocksDB
  protected def handle: ColumnFamilyHandle
  protected def writeOptions: WriteOptions
  protected def readOptions: ReadOptions

  override def getRawUnsafe(key: ByteString): ByteString = {
    val result = db.get(handle, readOptions, key.toArray)
    if (result == null) {
      throw IOError.KeyNotFound(key)
    } else {
      ByteString.fromArrayUnsafe(result)
    }
  }

  override def getOptRawUnsafe(key: ByteString): Option[ByteString] = {
    val result = db.get(handle, key.toArray)
    if (result == null) {
      None
    } else {
      Some(ByteString.fromArrayUnsafe(result))
    }
  }

  override def putRawUnsafe(key: ByteString, value: ByteString): Unit = {
    db.put(handle, writeOptions, key.toArray, value.toArray)
  }

  override def existsRawUnsafe(key: ByteString): Boolean = {
    val result = db.get(handle, key.toArray)
    result != null
  }

  override def deleteRawUnsafe(key: ByteString): Unit = {
    db.delete(handle, key.toArray)
  }
}
