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

package org.alephium.protocol.model

import akka.util.ByteString

import org.alephium.serde.{Serde, Staging}

trait SerializationCache {

  @volatile private var serialized: Option[ByteString] = None

  def getSerialized(): Option[ByteString] = {
    serialized
  }
  def setSerializedUnsafe(bytes: ByteString): Unit = {
    serialized = Some(bytes)
  }
}

object SerializationCache {
  @inline def cachedSerde[T <: SerializationCache](underlyingSerde: Serde[T]): Serde[T] =
    new Serde[T] {
      override def _deserialize(input: ByteString) = {
        underlyingSerde._deserialize(input).map { case result @ Staging(value, rest) =>
          value.setSerializedUnsafe(input.take(input.length - rest.length))
          result
        }
      }

      override def serialize(input: T) = {
        input.getSerialized() match {
          case Some(serialized) => serialized
          case None =>
            val serialized = underlyingSerde.serialize(input)
            input.setSerializedUnsafe(serialized)
            serialized
        }
      }
    }
}
