package org.alephium.flow.io

import org.alephium.serde._

abstract class KeyValueStorage[K: Serializer, V: Serde] extends RawKeyValueStorage {
  def get(key: K): IOResult[V] = IOUtils.tryExecute(getUnsafe(key))

  def getUnsafe(key: K): V = {
    val data = getRawUnsafe(serialize(key))
    deserialize[V](data) match {
      case Left(e)  => throw e
      case Right(v) => v
    }
  }

  def getOpt(key: K): IOResult[Option[V]] = IOUtils.tryExecute(getOptUnsafe(key))

  def getOptUnsafe(key: K): Option[V] = {
    getOptRawUnsafe(serialize(key)) map { data =>
      deserialize[V](data) match {
        case Left(e)  => throw e
        case Right(v) => v
      }
    }
  }

  def put(key: K, value: V): IOResult[Unit] = IOUtils.tryExecute(putUnsafe(key, value))

  def putUnsafe(key: K, value: V): Unit = {
    putRawUnsafe(serialize(key), serialize(value))
  }

  def exists(key: K): IOResult[Boolean] = IOUtils.tryExecute(existsUnsafe(key))

  def existsUnsafe(key: K): Boolean = {
    existsRawUnsafe(serialize(key))
  }

  def delete(key: K): IOResult[Unit] = IOUtils.tryExecute(deleteUnsafe(key))

  def deleteUnsafe(key: K): Unit = {
    deleteRawUnsafe(serialize(key))
  }
}
