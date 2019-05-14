package org.alephium.flow.io

import akka.util.ByteString
import org.alephium.serde.Serde

trait KeyValueStorage {
  def getRaw(key: ByteString): IOResult[ByteString]

  def get[V: Serde](key: ByteString): IOResult[V]

  def getOptRaw(key: ByteString): IOResult[Option[ByteString]]

  def getOpt[V: Serde](key: ByteString): IOResult[Option[V]]

  def putRaw(key: ByteString, value: ByteString): IOResult[Unit]

  def put[V: Serde](key: ByteString, value: V): IOResult[Unit]

  def delete(key: ByteString): IOResult[Unit]
}
