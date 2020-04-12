package org.alephium.flow.io

import akka.util.ByteString

import org.alephium.serde._

abstract class AbstractKeyValueStorage[K: Serializer, V: Serde] {
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

abstract class KeyValueStorage[K: Serializer, V: Serde]
    extends AbstractKeyValueStorage[K, V]
    with RawKeyValueStorage {
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

  def exists(key: K): IOResult[Boolean] = IOUtils.tryExecute(existsUnsafe(key))

  def existsUnsafe(key: K): Boolean = {
    existsRawUnsafe(storageKey(key))
  }

  def delete(key: K): IOResult[Unit] = IOUtils.tryExecute(deleteUnsafe(key))

  def deleteUnsafe(key: K): Unit = {
    deleteRawUnsafe(storageKey(key))
  }
}
