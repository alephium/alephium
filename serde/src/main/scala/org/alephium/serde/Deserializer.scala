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

package org.alephium.serde

import akka.util.ByteString

trait Deserializer[T] { self =>
  def _deserialize(input: ByteString): SerdeResult[(T, ByteString)]

  def deserialize(input: ByteString): SerdeResult[T] =
    _deserialize(input).flatMap {
      case (output, rest) =>
        if (rest.isEmpty) {
          Right(output)
        } else {
          Left(SerdeError.redundant(input.size - rest.size, input.size))
        }
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
