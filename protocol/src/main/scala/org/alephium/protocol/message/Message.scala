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
import org.alephium.serde._

/*
 * 4 bytes: Header
 * 4 bytes: Payload's length
 * ? bytes: Payload
 */
final case class Message(header: Header, payload: Payload)

object Message {
  def apply[T <: Payload](payload: T): Message = {
    val header = Header(Protocol.version)
    Message(header, payload)
  }

  def serialize(message: Message): ByteString = {
    val header  = serdeImpl[Header].serialize(message.header)
    val payload = Payload.serialize(message.payload)
    val length  = intSerde.serialize(payload.length)

    header ++ length ++ payload
  }

  def serialize[T <: Payload](payload: T): ByteString = {
    serialize(apply(payload))
  }

  def _deserialize(input: ByteString)(
      implicit config: GroupConfig): SerdeResult[(Message, ByteString)] = {
    for {
      headerPair <- serdeImpl[Header]._deserialize(input)
      (header, lengthWithPayload) = headerPair
      lengthPair <- intSerde._deserialize(lengthWithPayload)
      (length, payloadRest) = lengthPair
      _ <- checkLength(length, payloadRest)
      (payloadBytes, rest) = payloadRest.splitAt(length)
      payload <- Payload.deserialize(payloadBytes)
    } yield (Message(header, payload), rest)
  }

  def deserialize(input: ByteString)(implicit config: GroupConfig): SerdeResult[Message] = {
    _deserialize(input).flatMap {
      case (message, rest) =>
        if (rest.isEmpty) {
          Right(message)
        } else {
          Left(SerdeError.wrongFormat(s"Too many bytes: #${rest.length} left"))
        }
    }
  }

  private def checkLength(length: Int, data: ByteString) = {
    if (length < 0) {
      Left(SerdeError.wrongFormat(s"Negative length: $length"))
    } else if (data.length < length) {
      Left(SerdeError.wrongFormat(s"Too few bytes: expected $length, got ${data.length}"))
    } else {
      Right(())
    }
  }
}
