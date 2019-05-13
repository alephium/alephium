package org.alephium.flow.io
import akka.util.ByteString

trait KeyValueStorage {
  def get(key: ByteString): IOResult[ByteString]

  def getOpt(key: ByteString): IOResult[Option[ByteString]]

  def put(key: ByteString, value: ByteString): IOResult[Unit]

  def remove(key: ByteString, value: ByteString): IOResult[Unit]
}
