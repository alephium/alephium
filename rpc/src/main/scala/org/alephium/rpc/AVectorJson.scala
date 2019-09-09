package org.alephium.rpc

import scala.reflect.ClassTag

import io.circe._

import org.alephium.util.AVector

object AVectorJson {
  def decodeAVector[A: ClassTag](implicit A: Decoder[Array[A]]): Decoder[AVector[A]] =
    A.map(AVector.unsafe)

  def encodeAVector[A](implicit A: Encoder[A]): Encoder[AVector[A]] =
    new Encoder[AVector[A]] {
      final def apply(as: AVector[A]): Json = {
        val builder = Vector.newBuilder[Json]

        as.foreach { a =>
          builder += A(a)
        }

        Json.fromValues(builder.result())
      }
    }
}
