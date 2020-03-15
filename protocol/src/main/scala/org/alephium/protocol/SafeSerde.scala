package org.alephium.protocol

import akka.util.ByteString

import org.alephium.serde.{Serde, SerdeError, SerdeResult, Serializer}

trait SafeSerde[T, Config] {
  def serialize(t: T): ByteString

  def _deserialize(input: ByteString)(implicit config: Config): SerdeResult[(T, ByteString)]

  def deserialize(input: ByteString)(implicit config: Config): SerdeResult[T] = {
    _deserialize(input).flatMap {
      case (output, rest) =>
        if (rest.isEmpty) Right(output)
        else Left(SerdeError.redundant(input.size - rest.size, input.size))
    }
  }
}

trait SafeSerdeImpl[T, Config] extends SafeSerde[T, Config] {
  def _serde: Serde[T]

  implicit def serializer: Serializer[T] = _serde

  def validate(t: T)(implicit config: Config): Either[String, Unit]

  def serialize(t: T): ByteString = _serde.serialize(t)

  def _deserialize(input: ByteString)(implicit config: Config): SerdeResult[(T, ByteString)] = {
    _serde._deserialize(input).flatMap {
      case (t, rest) =>
        validate(t) match {
          case Right(_)    => Right((t, rest))
          case Left(error) => Left(SerdeError.validation(error))
        }
    }
  }
}
