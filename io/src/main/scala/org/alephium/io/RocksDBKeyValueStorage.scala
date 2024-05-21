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
import org.rocksdb._

import org.alephium.serde._

object RocksDBKeyValueStorage {
  import RocksDBSource.ProdSettings

  def apply[K: Serde, V: Serde](
      storage: RocksDBSource,
      cf: RocksDBSource.ColumnFamily
  ): KeyValueStorage[K, V] =
    apply(storage, cf, ProdSettings.writeOptions, ProdSettings.readOptions)

  def apply[K: Serde, V: Serde](
      storage: RocksDBSource,
      cf: RocksDBSource.ColumnFamily,
      writeOptions: WriteOptions
  ): KeyValueStorage[K, V] =
    apply(storage, cf, writeOptions, ProdSettings.readOptions)

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
    iterateRawE { (keyBytes, valBytes) =>
      for {
        key   <- keySerde.deserialize(keyBytes).left.map(IOError.Serde(_))
        value <- valueSerde.deserialize(valBytes).left.map(IOError.Serde(_))
        _     <- f(key, value)
      } yield ()
    }
  }

  def iterateRawE(f: (ByteString, ByteString) => IOResult[Unit]): IOResult[Unit] = {
    IOUtils.tryExecute {
      val iterator = db.newIterator(handle)
      iterator.seekToFirst()
      while (iterator.isValid()) {
        val keyBytes = ByteString.fromArrayUnsafe(iterator.key())
        val valBytes = ByteString.fromArrayUnsafe(iterator.value())
        f(keyBytes, valBytes) match {
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

  def iterateRaw(f: (ByteString, ByteString) => Unit): IOResult[Unit] = {
    iterateRawE((k, v) => Right(f(k, v)))
  }
}
