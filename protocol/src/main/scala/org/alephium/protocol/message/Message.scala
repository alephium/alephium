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

import org.alephium.protocol.Protocol
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.NetworkType
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
    val header = Header(Protocol.version)
    Message(header, payload)
  }

  def serialize(message: Message, networkType: NetworkType): ByteString = {
    val magic    = networkType.magicBytes
    val header   = serde.serialize[Header](message.header)
    val payload  = Payload.serialize(message.payload)
    val checksum = MessageSerde.checksum(payload)
    val length   = MessageSerde.length(payload)

    magic ++ checksum ++ length ++ header ++ payload
  }

  def serialize[T <: Payload](payload: T, networkType: NetworkType): ByteString = {
    serialize(apply(payload), networkType)
  }

  def _deserialize(input: ByteString, networkType: NetworkType)(implicit
      config: GroupConfig
  ): SerdeResult[Staging[Message]] = {
    MessageSerde.unwrap(input, networkType).flatMap { case (checksum, length, rest) =>
      for {
        headerRest   <- serde._deserialize[Header](rest)
        payloadBytes <- MessageSerde.extractPayloadBytes(length, headerRest.rest)
        _            <- MessageSerde.checkChecksum(checksum, payloadBytes.value)
        payload      <- deserializeExactPayload(payloadBytes.value)
      } yield {
        Staging(Message(headerRest.value, payload), payloadBytes.rest)
      }
    }
  }

  def deserialize(input: ByteString, networkType: NetworkType)(implicit
      config: GroupConfig
  ): SerdeResult[Message] = {
    _deserialize(input, networkType).flatMap { case Staging(message, rest) =>
      if (rest.isEmpty) {
        Right(message)
      } else {
        Left(SerdeError.wrongFormat(s"Too many bytes: #${rest.length} left"))
      }
    }
  }

  private def deserializeExactPayload(payloadBytes: ByteString)(implicit config: GroupConfig) = {
    Payload.deserialize(payloadBytes).left.map {
      case _: SerdeError.NotEnoughBytes =>
        SerdeError.wrongFormat("Cannot extract a correct payload from the length field")
      case error => error
    }
  }
}
