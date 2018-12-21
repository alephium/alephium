package org.alephium.serde

import akka.util.ByteString

trait Serializer[T] {
  def serialize(input: T): ByteString
}

object Serializer { def apply[T](implicit T: Serializer[T]): Serializer[T] = T }
