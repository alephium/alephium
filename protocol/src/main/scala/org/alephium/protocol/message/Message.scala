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

package org.alephium.protocol.message

import akka.util.ByteString

import org.alephium.protocol.WireVersion
import org.alephium.protocol.config.{GroupConfig, NetworkConfig}
import org.alephium.serde
import org.alephium.serde.{SerdeError, SerdeResult, Staging}

/*
 * 4 bytes: Header
 * 4 bytes: Payload's length
 * 4 bytes: Checksum
 * ? bytes: Payload
 */
final case class Message(header: Header, payload: Payload)

object Message {

  def apply[T <: Payload](payload: T): Message = {
    val header = Header(WireVersion.currentWireVersion)
    Message(header, payload)
  }

  def serialize(message: Message)(implicit networkConfig: NetworkConfig): ByteString = {
    val magic    = networkConfig.magicBytes
    val header   = serde.serialize[Header](message.header)
    val payload  = Payload.serialize(message.payload)
    val data     = header ++ payload
    val checksum = MessageSerde.checksum(data)
    val length   = MessageSerde.length(data)

    magic ++ checksum ++ length ++ data
  }

  def serialize[T <: Payload](payload: T)(implicit networkConfig: NetworkConfig): ByteString = {
    serialize(apply(payload))
  }

  def _deserialize(input: ByteString)(implicit
      config: GroupConfig,
      networkConfig: NetworkConfig
  ): SerdeResult[Staging[Message]] = {
    MessageSerde.unwrap(input).flatMap { case (checksum, length, rest) =>
      for {
        messageRest <- MessageSerde.extractMessageBytes(length, rest)
        _           <- MessageSerde.checkChecksum(checksum, messageRest.value)
        headerRest  <- serde._deserialize[Header](messageRest.value)
        payload     <- Payload.deserialize(headerRest.rest)
      } yield {
        Staging(Message(headerRest.value, payload), messageRest.rest)
      }
    }
  }

  def deserialize(input: ByteString)(implicit
      config: GroupConfig,
      networkConfig: NetworkConfig
  ): SerdeResult[Message] = {
    _deserialize(input).flatMap { case Staging(message, rest) =>
      if (rest.isEmpty) {
        Right(message)
      } else {
        Left(SerdeError.wrongFormat(s"Too many bytes: #${rest.length} left"))
      }
    }
  }
}
