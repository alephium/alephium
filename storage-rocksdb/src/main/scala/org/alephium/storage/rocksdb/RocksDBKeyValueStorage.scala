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

package org.alephium.storage.rocksdb

import akka.util.ByteString
import org.rocksdb.*

import org.alephium.io.IOResult
import org.alephium.io.IOUtils
import org.alephium.serde.*
import org.alephium.storage.KeyValueStorage

object RocksDBKeyValueStorage {
  import RocksDBSource.Settings

  def apply[K: Serde, V: Serde](
      storage: RocksDBSource,
      cf: RocksDBSource.ColumnFamily
  ): KeyValueStorage[K, V] =
    apply(storage, cf, Settings.writeOptions, Settings.readOptions)

  def apply[K: Serde, V: Serde](
      storage: RocksDBSource,
      cf: RocksDBSource.ColumnFamily,
      writeOptions: WriteOptions
  ): KeyValueStorage[K, V] =
    apply(storage, cf, writeOptions, Settings.readOptions)

  def apply[K: Serde, V: Serde](
      storage: RocksDBSource,
      cf: RocksDBSource.ColumnFamily,
      writeOptions: WriteOptions,
      readOptions: ReadOptions
  ): KeyValueStorage[K, V] =
    new RocksDBKeyValueStorage(storage, cf, writeOptions, readOptions)
}

class RocksDBKeyValueStorage[K, V](
    storage: RocksDBSource,
    cf: RocksDBSource.ColumnFamily,
    val writeOptions: WriteOptions,
    val readOptions: ReadOptions
)(implicit val keySerde: Serde[K], val valueSerde: Serde[V])
    extends KeyValueStorage[K, V]
    with RocksDBColumn {
  protected val db: RocksDB                = storage.db
  protected val handle: ColumnFamilyHandle = storage.handle(cf)

  def iterateE(f: (K, V) => IOResult[Unit]): IOResult[Unit] = {
    IOUtils.tryExecute {
      val iterator = db.newIterator(handle)
      iterator.seekToFirst()
      while (iterator.isValid()) {
        val keyBytes = ByteString.fromArrayUnsafe(iterator.key())
        val valBytes = ByteString.fromArrayUnsafe(iterator.value())
        (for {
          key   <- keySerde.deserialize(keyBytes)
          value <- valueSerde.deserialize(valBytes)
          _     <- f(key, value)
        } yield ()) match {
          case Left(err) =>
            iterator.close()
            throw err
          case _ => iterator.next()
        }
      }
      iterator.close()
    }
  }

  def iterate(f: (K, V) => Unit): IOResult[Unit] = {
    iterateE((k, v) => Right(f(k, v)))
  }
}
