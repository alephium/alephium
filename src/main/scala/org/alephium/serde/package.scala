package org.alephium

import akka.util.ByteString

import scala.reflect.ClassTag
import scala.util.Try

package object serde {
  import Serde._

  def serialize[T](input: T)(implicit serializer: Serde[T]): ByteString =
    serializer.serialize(input)

  def deserialize[T](input: ByteString)(implicit deserializer: Serde[T]): Try[T] =
    deserializer.deserialize(input)

  implicit val byteSerde: Serde[Byte] = ByteSerde

  implicit val intSerde: Serde[Int] = IntSerde

  implicit val longSerde: Serde[Long] = LongSerde

  implicit def seqSerde[T: ClassTag](implicit serde: Serde[T]): Serde[Seq[T]] =
    dynamicSizeBytesSerde(serde)
}
