package org.alephium

import shapeless._
import akka.util.ByteString

import scala.util.Try

package object serde {
  import Serde._

  def serialize[T](input: T)(implicit serde: Serde[T]): ByteString = serde.serialize(input)

  def deserialize[T](input: ByteString)(implicit serde: Serde[T]): Try[T] = serde.deserialize(input)

  implicit val byteSerde: Serde[Byte] = ByteSerde

  implicit val intSerde: Serde[Int] = IntSerde

  implicit val hnilSerde: Serde[HNil] = HNilSerde

  implicit def hlistSerde[A, L <: HList](implicit a: Serde[A], l: Serde[L]): Serde[A :: L] =
    prepend(a, l)

  implicit def derivedSerde[T, R](implicit gen: Generic.Aux[T, R], serde: Serde[R]): Serde[T] =
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
