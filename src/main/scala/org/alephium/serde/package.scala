package org.alephium

import shapeless._
import akka.util.ByteString

import scala.util.{Success, Try}

package object serde {
  import Serde._

  def serialize[T](input: T)(implicit serde: Serde[T]): ByteString = serde.serialize(input)

  def deserialize[T](input: ByteString)(implicit serde: Serde[T]): Try[T] = serde.deserialize(input)

  implicit val byteSerde: Serde[Byte] = ByteSerde

  implicit val intSerde: Serde[Int] = IntSerde

  implicit val hnilSerde: Serde[HNil] = new Serde[HNil] {
    override def serdeSize: Int = 0

    override def serialize(input: HNil): ByteString = ByteString.empty

    override def deserialize(input: ByteString): Try[HNil] =
      if (input.isEmpty) Success(HNil) else throw InvalidNumberOfBytesException(0, input.size)
  }

  implicit def hlistSerde[A, L <: HList](implicit a: Serde[A], l: Serde[L]): Serde[A :: L] =
    prepend(a, l)

  implicit def derivedSerde[T, R](implicit gen: Generic.Aux[T, R], serde: Serde[R]): Serde[T] =
    new Serde[T] {
      override def serdeSize: Int = serde.serdeSize

      override def serialize(input: T): ByteString = {
        serde.serialize(gen.to(input))
      }

      override def deserialize(input: ByteString): Try[T] = {
        serde.deserialize(input).map(gen.from)
      }
    }
}
