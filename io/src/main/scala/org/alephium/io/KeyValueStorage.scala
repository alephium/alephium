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

import org.alephium.serde._

trait AbstractKeyValueStorage[K, V] {

  implicit def keySerde: Serde[K]
  implicit def valueSerde: Serde[V]

  def get(key: K): IOResult[V]

  def getUnsafe(key: K): V

  def getOpt(key: K): IOResult[Option[V]]

  def getOptUnsafe(key: K): Option[V]

  def put(key: K, value: V): IOResult[Unit]

  def putUnsafe(key: K, value: V): Unit

  def exists(key: K): IOResult[Boolean]

  def existsUnsafe(key: K): Boolean

  def delete(key: K): IOResult[Unit]

  def deleteUnsafe(key: K): Unit
}

trait KeyValueStorage[K, V]
    extends AbstractKeyValueStorage[K, V]
    with RawKeyValueStorage
    with ReadableKV[K, V] {

  protected def storageKey(key: K): ByteString = serialize(key)

  def get(key: K): IOResult[V] = IOUtils.tryExecute(getUnsafe(key))

  def getUnsafe(key: K): V = {
    val data = getRawUnsafe(storageKey(key))
    deserialize[V](data) match {
      case Left(e)  => throw e
      case Right(v) => v
    }
  }

  def getOpt(key: K): IOResult[Option[V]] = IOUtils.tryExecute(getOptUnsafe(key))

  def getOptUnsafe(key: K): Option[V] = {
    getOptRawUnsafe(storageKey(key)) map { data =>
      deserialize[V](data) match {
        case Left(e)  => throw e
        case Right(v) => v
      }
    }
  }

  def put(key: K, value: V): IOResult[Unit] = IOUtils.tryExecute(putUnsafe(key, value))

  def putUnsafe(key: K, value: V): Unit = {
    putRawUnsafe(storageKey(key), serialize(value))
  }

  def putBatch(f: ((K, V) => Unit) => Unit): IOResult[Unit] = {
    IOUtils.tryExecute(putBatchUnsafe(f))
  }

  def putBatchUnsafe(f: ((K, V) => Unit) => Unit): Unit = {
    putBatchRawUnsafe(g => f((k, v) => g(storageKey(k), serialize(v))))
  }

  def exists(key: K): IOResult[Boolean] = IOUtils.tryExecute(existsUnsafe(key))

  def existsUnsafe(key: K): Boolean = {
    existsRawUnsafe(storageKey(key))
  }

  def delete(key: K): IOResult[Unit] = IOUtils.tryExecute(deleteUnsafe(key))

  def deleteUnsafe(key: K): Unit = {
    deleteRawUnsafe(storageKey(key))
  }
}
