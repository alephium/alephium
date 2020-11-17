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

import org.alephium.protocol.{Hash, Protocol}
import org.alephium.protocol.config.GroupConfig
import org.alephium.serde._
import org.alephium.util.Hex

/*
 * 4 bytes: Header
 * 4 bytes: Payload's length
 * 4 bytes: Checksum
 * ? bytes: Payload
 */
final case class Message(header: Header, payload: Payload)

object Message {

  private val checksumLength = 4

  def apply[T <: Payload](payload: T): Message = {
    val header = Header(Protocol.version)
    Message(header, payload)
  }

  def serialize(message: Message): ByteString = {
    val header   = serdeImpl[Header].serialize(message.header)
    val payload  = Payload.serialize(message.payload)
    val length   = intSerde.serialize(payload.length)
    val checksum = Hash.hash(payload).bytes.take(checksumLength)

    header ++ length ++ checksum ++ payload
  }

  def serialize[T <: Payload](payload: T): ByteString = {
    serialize(apply(payload))
  }

  def _deserialize(input: ByteString)(
      implicit config: GroupConfig): SerdeResult[(Message, ByteString)] = {
    for {
      headerLength    <- Header.serde._deserialize(input)
      lengthChecksum  <- intSerde._deserialize(headerLength._2)
      checksumPayload <- extractChecksum(lengthChecksum._2)
      payloadBytes    <- extractPayloadBytes(lengthChecksum._1, checksumPayload._2)
      _               <- checkChecksum(checksumPayload._1, payloadBytes._1)
      payload         <- deserializeExactPayload(payloadBytes._1)
    } yield {
      val header = headerLength._1
      (Message(header, payload), payloadBytes._2)
    }
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

  private def extractChecksum(bytes: ByteString) = {
    Either.cond(
      bytes.length >= checksumLength,
      bytes.splitAt(checksumLength),
      SerdeError.notEnoughBytes(checksumLength, bytes.length)
    )
  }

  private def extractPayloadBytes(length: Int,
                                  data: ByteString): SerdeResult[(ByteString, ByteString)] = {
    if (length < 0) {
      Left(SerdeError.wrongFormat(s"Negative length: $length"))
    } else if (data.length < length) {
      Left(SerdeError.notEnoughBytes(length, data.length))
    } else {
      Right(data.splitAt(length))
    }
  }

  private def checkChecksum(checksum: ByteString, data: ByteString) = {
    val digest = Hash.hash(data).bytes.take(checksumLength)
    Either.cond(
      digest == checksum,
      (),
      SerdeError.wrongFormat(
        s"Wrong checksum: expected ${Hex.toHexString(digest)}, got ${Hex.toHexString(checksum)}")
    )
  }

  private def deserializeExactPayload(payloadBytes: ByteString)(implicit config: GroupConfig) = {
    Payload.deserialize(payloadBytes).left.map {
      case _: SerdeError.NotEnoughBytes =>
        SerdeError.wrongFormat("Cannot extract a correct payload from the length field")
      case error => error
    }
  }
}
