package org.alephium.serde

import akka.util.ByteString

trait Deserializer[T] { self =>
  def _deserialize(input: ByteString): SerdeResult[(T, ByteString)]

  def deserialize(input: ByteString): SerdeResult[T] =
    _deserialize(input).flatMap {
      case (output, rest) =>
        if (rest.isEmpty) Right(output)
        else Left(SerdeError.redundant(input.size - rest.size, input.size))
    }

  def validateGet[U](get: T => Option[U], error: T => String): Deserializer[U] =
    (input: ByteString) => {
      self._deserialize(input).flatMap {
        case (t, rest) =>
          get(t) match {
            case Some(u) => Right((u, rest))
            case None    => Left(SerdeError.wrongFormat(error(t)))
          }
      }
    }
}

object Deserializer { def apply[T](implicit T: Deserializer[T]): Deserializer[T] = T }
