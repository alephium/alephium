package org.alephium.flow.io

import org.alephium.serde.{Serde, Serializer}

abstract class KeyValueStorage[K: Serializer, V: Serde] {
  def get(key: K): IOResult[V]

  def getUnsafe(key: K): V

  def getOpt(key: K): IOResult[Option[V]]

  def getOptUnsafe(key: K): Option[V]

  def put(key: K, value: V): IOResult[Unit]

  def putUnsafe(Key: K, value: V): Unit

  def exists(key: K): IOResult[Boolean]

  def existsUnsafe(key: K): Boolean

  def delete(key: K): IOResult[Unit]

  def deleteUnsafe(key: K): Unit
}
