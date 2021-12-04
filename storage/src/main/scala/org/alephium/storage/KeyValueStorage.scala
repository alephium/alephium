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

package org.alephium.storage

import akka.util.ByteString

import org.alephium.io.{IOError, IOResult}
import org.alephium.io.IOUtils.tryExecute
import org.alephium.serde.{deserialize, serialize, Serde}

/** Storage-engine agnostic implementation per [[ColumnFamily]]
  *
  * @param source Target storage engine.
  * @param columnFamily Target [[ColumnFamily]]
  */
abstract class KeyValueStorage[K: Serde, V: Serde](
    source: KeyValueSource,
    columnFamily: ColumnFamily
) {

  private val column = source.getColumnUnsafe(columnFamily)

  def storageKey(key: K): ByteString = serialize(key)

  def get(key: K): IOResult[V] = {
    tryExecute(getUnsafe(key))
  }

  def getUnsafe(key: K): V = {
    source.getUnsafe(column, storageKey(key).toArray) match {
      case Some(value) =>
        deserialize[V](ByteString.fromArrayUnsafe(value)) match {
          case Left(error)  => throw error
          case Right(value) => value
        }

      case None =>
        throw IOError.keyNotFound(key, s"${this.getClass.getSimpleName}.getUnsafe")
    }
  }

  def getOptUnsafe(key: K): Option[V] = {
    source.getUnsafe(column, storageKey(key).toArray) map { value =>
      deserialize[V](ByteString.fromArrayUnsafe(value)) match {
        case Left(error)  => throw error
        case Right(value) => value
      }
    }
  }

  def getOpt(key: K): IOResult[Option[V]] = {
    tryExecute(getOptUnsafe(key))
  }

  def exists(key: K): IOResult[Boolean] = {
    tryExecute(existsUnsafe(key))
  }

  def existsUnsafe(key: K): Boolean = {
    source.existsUnsafe(column, storageKey(key).toArray)
  }

  def put(key: K, value: V): IOResult[Unit] = {
    tryExecute(putUnsafe(key, value))
  }

  def putUnsafe(key: K, value: V): Unit = {
    source.putUnsafe(column, storageKey(key).toArray, serialize(value).toArray)
  }

  def iterateUnsafe(f: (K, V) => Unit): Unit = {
    source.iterateUnsafe(
      column,
      (key, value) => {
        val result =
          for {
            typedKey   <- deserialize[K](ByteString.fromArrayUnsafe(key))
            typedValue <- deserialize[V](ByteString.fromArrayUnsafe(value))
          } yield f(typedKey, typedValue)

        result.left.foreach(error => throw error)
      }
    )
  }

  def iterate(f: (K, V) => Unit): IOResult[Unit] = {
    tryExecute(iterateUnsafe(f))
  }

  def iterateE(f: (K, V) => IOResult[Unit]): IOResult[Unit] = {
    tryExecute {
      iterateUnsafe { case (key, value) =>
        f(key, value) match {
          case Left(error)  => throw error
          case Right(value) => value
        }
      }
    }
  }

  def delete(key: K): IOResult[Unit] = {
    tryExecute(deleteUnsafe(key))
  }

  def deleteUnsafe(key: K): Unit = {
    source.deleteUnsafe(column, storageKey(key).toArray)
  }

  def deleteRangeUnsafe(fromKey: K, toKey: K): Unit = {
    source.deleteRangeUnsafe(column, storageKey(fromKey).toArray, storageKey(toKey).toArray)
  }

  def deleteRange(fromKey: K, toKey: K): IOResult[Unit] = {
    tryExecute(deleteRangeUnsafe(fromKey, toKey))
  }
}

object KeyValueStorage {
  def apply[K: Serde, V: Serde](
      storage: KeyValueSource,
      storageName: ColumnFamily
  ): KeyValueStorage[K, V] =
    new KeyValueStorage[K, V](storage, storageName) {}
}
