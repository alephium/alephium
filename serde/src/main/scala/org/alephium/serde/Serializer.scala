package org.alephium.serde

import akka.util.ByteString

trait Serializer[T] {
  def serialize(input: T): ByteString
}

object Serializer extends ProductSerializer {
  def apply[T](implicit T: Serializer[T]): Serializer[T] = T
}
