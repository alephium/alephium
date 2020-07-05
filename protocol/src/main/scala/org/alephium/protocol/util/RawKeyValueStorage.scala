package org.alephium.protocol.util

import akka.util.ByteString

trait RawKeyValueStorage {
  def getRawUnsafe(key: ByteString): ByteString

  def getOptRawUnsafe(key: ByteString): Option[ByteString]

  def putRawUnsafe(key: ByteString, value: ByteString): Unit

  def existsRawUnsafe(key: ByteString): Boolean

  def deleteRawUnsafe(key: ByteString): Unit
}
