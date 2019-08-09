package org.alephium.rpc

import scala.reflect.ClassTag
import org.alephium.util.AVector
import io.circe._

object AVectorJson extends AVectorJson {}

trait AVectorJson {
  // scalastyle:off null
  implicit def decodeAVector[A: ClassTag](implicit A: Decoder[A]): Decoder[AVector[A]] =
    new Decoder[AVector[A]] {
      def apply(c: HCursor): Decoder.Result[AVector[A]] = {
        var as      = AVector.empty[A]
        var current = c.downArray

        if (current.succeeded) {
          var failed: DecodingFailure = null

          while (failed.eq(null) && current.succeeded) {
            A(current.asInstanceOf[HCursor]) match {
              case Left(e) => failed = e
              case Right(a) =>
                as      = as :+ a
                current = current.right
            }
          }

          if (failed.eq(null)) Right(as) else Left(failed)
        } else {
          if (c.value.isArray) Right(as)
          else {
            Left(DecodingFailure("AVector[A]", c.history))
          }
        }
      }

    }

  implicit def encodeAVector[A](implicit A: Encoder[A]): Encoder[AVector[A]] =
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
