package org.alephium

import shapeless._
import akka.util.ByteString

import scala.reflect.ClassTag
import scala.util.Try

package object serde {
  import Serde._

  def serialize[T](input: T)(implicit serializer: Serializer[T]): ByteString =
    serializer.serialize(input)

  def deserialize[T](input: ByteString)(implicit deserializer: Deserializer[T]): Try[T] =
    deserializer.deserialize(input)

  implicit val byteSerde: Serde[Byte] = ByteSerde

  implicit val intSerde: Serde[Int] = IntSerde

  implicit def seqSerde[T: ClassTag](implicit serde: Serde[T]): Serde[Seq[T]] =
    dynamicSizeBytesSerde(serde)

  implicit val hnilSerde: Serde[HNil] = HNilSerde

  implicit def hlistSerde[A, L <: HList](implicit a: Serde[A], l: Serde[L]): Serde[A :: L] =
    prepend(a, l)

  implicit def derivedSerde[T <: scala.Product, R](implicit gen: Generic.Aux[T, R],
                                                   serde: Serde[R]): Serde[T] =
    new Serde[T] {
      override def serialize(input: T): ByteString = {
        serde.serialize(gen.to(input))
      }

      override def _deserialize(input: ByteString): Try[(T, ByteString)] = {
        serde._deserialize(input).map {
          case (r, rest) => (gen.from(r), rest)
        }
      }

      override def deserialize(input: ByteString): Try[T] = {
        serde.deserialize(input).map(gen.from)
      }
    }
}
