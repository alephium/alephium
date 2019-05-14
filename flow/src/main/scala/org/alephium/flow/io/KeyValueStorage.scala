package org.alephium.flow.io

import akka.util.ByteString
import org.alephium.serde.Serde

trait KeyValueStorage {
  def get[V: Serde](key: ByteString): IOResult[V]

  def getOpt[V: Serde](key: ByteString): IOResult[Option[V]]

  def putRaw(key: ByteString, value: ByteString): IOResult[Unit]

  def put[V: Serde](key: ByteString, value: V): IOResult[Unit]

  def remove(key: ByteString): IOResult[Unit]
}
