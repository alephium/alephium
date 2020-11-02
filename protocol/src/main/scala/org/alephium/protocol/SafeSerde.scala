// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.protocol

import akka.util.ByteString

import org.alephium.serde.{Serde, SerdeError, SerdeResult, Serializer}

trait SafeSerde[T, Config] {
  def serialize(t: T): ByteString

  def _deserialize(input: ByteString)(implicit config: Config): SerdeResult[(T, ByteString)]

  def deserialize(input: ByteString)(implicit config: Config): SerdeResult[T] = {
    _deserialize(input).flatMap {
      case (output, rest) =>
        if (rest.isEmpty) {
          Right(output)
        } else {
          Left(SerdeError.redundant(input.size - rest.size, input.size))
        }
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
