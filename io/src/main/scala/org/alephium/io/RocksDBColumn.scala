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

import scala.jdk.CollectionConverters._

import akka.util.ByteString
import org.rocksdb.{ColumnFamilyHandle, ReadOptions, RocksDB, WriteBatch, WriteOptions}

object RocksDBColumn {
  import RocksDBSource.ProdSettings

  def apply(storage: RocksDBSource, cf: RocksDBSource.ColumnFamily): RocksDBColumn =
    apply(storage, cf, ProdSettings.writeOptions, ProdSettings.readOptions)

  def apply(
      storage: RocksDBSource,
      cf: RocksDBSource.ColumnFamily,
      writeOptions: WriteOptions
  ): RocksDBColumn =
    apply(storage, cf, writeOptions, ProdSettings.readOptions)

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
      throw IOError.keyNotFound(key, "RocksDBColumn.getRawUnsafe")
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

  override def multiGetRawUnsafe(keys: Seq[ByteString]): Seq[ByteString] = {
    val convertedKeys = keys.map(_.toArray).asJava
    val handles       = keys.map(_ => handle).asJava
    db.multiGetAsList(handles, convertedKeys)
      .asScala
      .view
      .zipWithIndex
      .map { case (value, index) =>
        Option(value) match {
          case Some(v) =>
            ByteString.fromArrayUnsafe(v)
          case None =>
            throw IOError.keyNotFound(keys(index), "RocksDBColumn.multiGetRawUnsafe")
        }
      }
      .toSeq
  }

  override def putBatchRawUnsafe(f: ((ByteString, ByteString) => Unit) => Unit): Unit = {
    val writeBatch = new WriteBatch()
    f((x, y) => writeBatch.put(handle, x.toArray, y.toArray))
    db.write(writeOptions, writeBatch)
    writeBatch.close()
  }

  override def existsRawUnsafe(key: ByteString): Boolean = {
    val result = db.get(handle, key.toArray)
    result != null
  }

  override def deleteRawUnsafe(key: ByteString): Unit = {
    db.delete(handle, key.toArray)
  }

  override def deleteBatchRawUnsafe(keys: Seq[ByteString]): Unit = {
    val writeBatch = new WriteBatch()
    keys.foreach(key => writeBatch.delete(handle, key.toArray))
    db.write(writeOptions, writeBatch)
    writeBatch.close()
  }
}
