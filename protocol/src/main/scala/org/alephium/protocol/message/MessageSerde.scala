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

import org.alephium.protocol.Hash
import org.alephium.protocol.model.NetworkType
import org.alephium.serde._
import org.alephium.util.Hex

object MessageSerde {

  private val checksumLength: Int = 4

  def checksum(data: ByteString): ByteString =
    Hash.hash(data).bytes.take(checksumLength)

  def length(data: ByteString): ByteString =
    intSerde.serialize(data.length)

  def unwrap[M](
      input: ByteString,
      networkType: NetworkType
  ): SerdeResult[(ByteString, Int, ByteString)] = {
    for {
      rest         <- checkMagicBytes(input, networkType)
      checksumRest <- extractChecksum(rest)
      lengthRest   <- intSerde._deserialize(checksumRest.rest)
    } yield {
      (checksumRest.value, lengthRest.value, lengthRest.rest)
    }
  }

  private def extractChecksum(bytes: ByteString): SerdeResult[Staging[ByteString]] = {
    Either.cond(
      bytes.length >= checksumLength,
      bytes.splitAt(checksumLength) match { case (checksum, rest) => Staging(checksum, rest) },
      SerdeError.notEnoughBytes(checksumLength, bytes.length)
    )
  }

  def extractPayloadBytes(length: Int, data: ByteString): SerdeResult[Staging[ByteString]] = {
    if (length < 0) {
      Left(SerdeError.wrongFormat(s"Negative length: $length"))
    } else if (data.length < length) {
      Left(SerdeError.notEnoughBytes(length, data.length))
    } else {
      Right(data.splitAt(length) match { case (payload, rest) => Staging(payload, rest) })
    }
  }

  private def checkMagicBytes(
      data: ByteString,
      networkType: NetworkType
  ): SerdeResult[ByteString] = {
    if (data.length < networkType.magicBytes.length) {
      Left(SerdeError.notEnoughBytes(networkType.magicBytes.length, data.length))
    } else {
      Either.cond(
        networkType.magicBytes == data.take(networkType.magicBytes.length),
        data.drop(networkType.magicBytes.length),
        SerdeError.wrongFormat(s"Wrong magic bytes")
      )
    }
  }

  def checkChecksum(toCheck: ByteString, data: ByteString): SerdeResult[Unit] = {
    val digest = checksum(data)
    Either.cond(
      digest == toCheck,
      (),
      SerdeError.wrongFormat(
        s"Wrong checksum: expected ${Hex.toHexString(digest)}, got ${Hex.toHexString(toCheck)}"
      )
    )
  }
}
