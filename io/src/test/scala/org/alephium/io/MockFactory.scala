package org.alephium.io

import akka.util.ByteString

import org.alephium.serde.{Serde, Serializer}

trait MockFactory {
  def unimplementedStorage[K, V]: KeyValueStorage[K, V] = new KeyValueStorage[K, V] {
    override implicit def keySerializer: Serializer[K] = ???
    override implicit def valueSerde: Serde[V]         = ???

    override def getRawUnsafe(key: ByteString): ByteString              = ???
    override def getOptRawUnsafe(key: ByteString): Option[ByteString]   = ???
    override def putRawUnsafe(key: ByteString, value: ByteString): Unit = ???
    override def existsRawUnsafe(key: ByteString): Boolean              = ???
    override def deleteRawUnsafe(key: ByteString): Unit                 = ???
  }
}
