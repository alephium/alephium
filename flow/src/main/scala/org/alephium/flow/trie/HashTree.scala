package org.alephium.flow.trie

import akka.util.ByteString
import org.alephium.crypto.Keccak256
import org.alephium.flow.io.{IOError, IOResult}
import org.alephium.serde._

object HashTree {
  case class Root(hash: Keccak256)
}

abstract class HashTree[K: Serde, V: Serde] {
  protected def getOpt(key: ByteString): IOResult[Option[ByteString]]

  protected def remove(key: ByteString): IOResult[HashTree[K, V]]

  protected def putBytes(Key: ByteString, data: ByteString): IOResult[HashTree[K, V]]

  def getDataOpt(key: K): IOResult[Option[V]] =
    getOpt(serialize(key)).flatMap {
      case Some(data) =>
        deserialize[V](data) match {
          case Left(e)  => Left(IOError.Serde(e))
          case Right(v) => Right(Some(v))
        }
      case None => Right(None)
    }

  def putData(key: K, data: V): IOResult[HashTree[K, V]] =
    putBytes(serialize(key), serialize(data))
}
