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

package org.alephium.flow.network.bootstrap

import akka.util.ByteString

import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.message.MessageSerde
import org.alephium.serde.{SerdeResult, Staging}
import org.alephium.util.Bytes

trait SimpleSerde[T] {
  def serializeBody(input: T): ByteString

  def serialize(input: T): ByteString = {
    val body = serializeBody(input)
    Bytes.from(body.length) ++ body
  }

  def deserializeBody(input: ByteString)(implicit groupConfig: GroupConfig): SerdeResult[T]

  def deserialize(
      input: ByteString
  )(implicit groupConfig: GroupConfig): SerdeResult[Staging[T]] = {
    for {
      lengthRest  <- MessageSerde.extractLength(input)
      messageRest <- MessageSerde.extractMessageBytes(lengthRest.value, lengthRest.rest)
      message     <- deserializeBody(messageRest.value)
    } yield Staging(message, messageRest.rest)
  }

  def tryDeserialize(
      data: ByteString
  )(implicit groupConfig: GroupConfig): SerdeResult[Option[Staging[T]]] = {
    SerdeUtils.unwrap(deserialize(data))
  }
}
