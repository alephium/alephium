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

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util

import org.rocksdb._

import org.alephium.io.{IOError, IOResult}
import org.alephium.io.IOException.StorageException
import org.alephium.storage.{ColumnFamily, KeyValueSource}
import org.alephium.storage.rocksdb.RocksDBSource._

/** [[KeyValueSource]] implementation for RocksDB.
  *
  * Restricted to this storage package.
  */
object RocksDBSource {

  /** Core projects has no dependency on RocksDB and do not know of [[RocksDBException]].
    * These exceptions should get converted to [[StorageException]].
    */
  @inline def convertException[T](f: => T): T =
    try f
    catch {
      case rocksDBException: RocksDBException =>
        throw StorageException(rocksDBException)
    }

  /** RocksDB's default configuration. Not optimised.
    *
    * @param path Full path for the database directory
    */
  def openUnsafe(path: Path, columns: Iterable[ColumnFamily]): KeyValueSource = {
    convertException {
      RocksDB.loadLibrary()

      val dbOptions: DBOptions =
        new DBOptions()
          .setCreateIfMissing(true)
          .setCreateMissingColumnFamilies(true)

      //create java instance required by RocksDB instance
      val handles     = new util.ArrayList[ColumnFamilyHandle]()
      val descriptors = new util.ArrayList[ColumnFamilyDescriptor]()

      (columns.map(_.name) ++ Seq("default")) foreach { name =>
        descriptors.add(new ColumnFamilyDescriptor(name.getBytes(StandardCharsets.UTF_8)))
      }

      val rocksDB =
        RocksDB.open(dbOptions, path.toString, descriptors, handles)

      val tableHandleMap =
        columns.zipWithIndex.map { case (table, index) =>
          table -> handles.get(index)
        }.toMap

      new RocksDBSource(
        path = path,
        handles = tableHandleMap,
        db = rocksDB,
        readOptions = new ReadOptions().setVerifyChecksums(false),
        writeOptions = new WriteOptions()
      )
    }
  }

}

protected class RocksDBSource(
    val path: Path,
    handles: Map[ColumnFamily, ColumnFamilyHandle],
    db: RocksDB,
    readOptions: ReadOptions,
    writeOptions: WriteOptions
) extends KeyValueSource {

  override type COLUMN = ColumnFamilyHandle

  def getColumnUnsafe(column: ColumnFamily): ColumnFamilyHandle = {
    convertException(handles.get(column)) match {
      case Some(handle) =>
        handle

      case None =>
        throw StorageException(
          s"${classOf[ColumnFamilyHandle].getSimpleName} not found for $column"
        )
    }
  }

  /** Returns the handle for a column name which should be stored
    * in target [[org.alephium.storage.column.StorageColumn]]
    * so there no need to re-fetch this for each read request.
    */
  def getColumn(column: ColumnFamily): IOResult[ColumnFamilyHandle] = {
    convertException(handles.get(column)) match {
      case Some(handle) =>
        Right(handle)

      case None =>
        //Column family not created for RocksDB. Report this on boot-up.
        Left(
          IOError.Storage(
            StorageException(s"${classOf[ColumnFamilyHandle].getSimpleName} not found for $column")
          )
        )
    }
  }

  override def getUnsafe(column: ColumnFamilyHandle, key: Array[Byte]): Option[Array[Byte]] =
    Option(convertException(db.get(column, readOptions, key)))

  override def existsUnsafe(column: ColumnFamilyHandle, key: Array[Byte]): Boolean = {
    val result = convertException(db.get(column, readOptions, key))
    result != null
  }

  override def putUnsafe(column: ColumnFamilyHandle, key: Array[Byte], value: Array[Byte]): Unit =
    convertException(db.put(column, writeOptions, key, value))

  override def deleteUnsafe(column: ColumnFamilyHandle, key: Array[Byte]): Unit =
    convertException(db.delete(column, writeOptions, key))

  override def deleteRangeUnsafe(
      column: ColumnFamilyHandle,
      fromKey: Array[Byte],
      toKey: Array[Byte]
  ): Unit =
    convertException(db.deleteRange(column, writeOptions, fromKey, toKey))

  def iterateUnsafe(column: ColumnFamilyHandle, f: (Array[Byte], Array[Byte]) => Unit): Unit = {
    convertException {
      val iterator = db.newIterator(column)
      iterator.seekToFirst()

      while (iterator.isValid()) {
        try {
          f(iterator.key(), iterator.value())
          iterator.next()
        } catch {
          case throwable: Throwable =>
            iterator.close()
            throw throwable
        }
      }

      iterator.close()
    }
  }

  def closeUnsafe(): Unit = {
    convertException {
      //TODO - is this safe? What if closing handles fail? Should db.close() be in a final block?
      handles.values.foreach(_.close())
      db.close()
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def dESTROYUnsafe(): Unit = {
    convertException {
      closeUnsafe()
      RocksDB.destroyDB(path.toString, new Options())
    }
  }
}
