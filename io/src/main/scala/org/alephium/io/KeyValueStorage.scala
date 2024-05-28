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

  def multiGetUnsafe(keys: Seq[K]): Seq[V]

  def put(key: K, value: V): IOResult[Unit]

  def putUnsafe(key: K, value: V): Unit

  def exists(key: K): IOResult[Boolean]

  def existsUnsafe(key: K): Boolean

  def remove(key: K): IOResult[Unit]

  def removeUnsafe(key: K): Unit

  def removeBatchUnsafe(keys: Seq[K]): Unit
}

trait KeyValueStorage[K, V]
    extends AbstractKeyValueStorage[K, V]
    with RawKeyValueStorage
    with MutableKV[K, V, Unit] {
  def unit: Unit = ()

  protected def storageKey(key: K): ByteString = serialize(key)

  def get(key: K): IOResult[V] = IOUtils.tryExecute(getUnsafe(key))

  def getUnsafe(key: K): V = {
    val data = getRawUnsafe(storageKey(key))
    deserialize[V](data) match {
      case Left(e)  => throw e
      case Right(v) => v
    }
  }

  def getRawUnsafe(key: K): ByteString = getRawUnsafe(storageKey(key))

  def getOpt(key: K): IOResult[Option[V]] = IOUtils.tryExecute(getOptUnsafe(key))

  def getOptUnsafe(key: K): Option[V] = {
    getOptRawUnsafe(storageKey(key)) map { data =>
      deserialize[V](data) match {
        case Left(e)  => throw e
        case Right(v) => v
      }
    }
  }

  def multiGetUnsafe(keys: Seq[K]): Seq[V] = {
    val storageKeys: Seq[ByteString] = keys.map(storageKey(_))
    multiGetRawUnsafe(storageKeys) map { data =>
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

  def remove(key: K): IOResult[Unit] = IOUtils.tryExecute(removeUnsafe(key))

  def removeUnsafe(key: K): Unit = {
    deleteRawUnsafe(storageKey(key))
  }

  def removeBatchUnsafe(keys: Seq[K]): Unit = {
    val storageKeys = keys.map(storageKey(_))
    deleteBatchRawUnsafe(storageKeys)
  }
}
