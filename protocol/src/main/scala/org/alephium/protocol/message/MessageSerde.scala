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

import org.alephium.protocol.config.NetworkConfig
import org.alephium.serde._
import org.alephium.util.{Bytes, DjbHash, Hex}

object MessageSerde {
  private val checksumLength: Int = 4

  def checksum(data: ByteString): ByteString =
    Bytes.from(DjbHash.intHash(data))

  def length(data: ByteString): ByteString =
    Bytes.from(data.length)

  def unwrap(
      input: ByteString
  )(implicit networkConfig: NetworkConfig): SerdeResult[(ByteString, Int, ByteString)] = {
    for {
      rest         <- checkMagicBytes(input)
      checksumRest <- extractChecksum(rest)
      lengthRest <- extractLength(
        checksumRest.rest
      ) // don't use intSerde due to potential notEnoughBytes
    } yield {
      (checksumRest.value, lengthRest.value, lengthRest.rest)
    }
  }

  def extractBytes(bytes: ByteString, length: Int): SerdeResult[Staging[ByteString]] = {
    Either.cond(
      bytes.length >= length,
      bytes.splitAt(length) match { case (data, rest) => Staging(data, rest) },
      SerdeError.notEnoughBytes(length, bytes.length)
    )
  }

  def extractChecksum(bytes: ByteString): SerdeResult[Staging[ByteString]] = {
    extractBytes(bytes, checksumLength)
  }

  def extractLength(bytes: ByteString): SerdeResult[Staging[Int]] = {
    extractBytes(bytes, 4).map(_.mapValue(Bytes.toIntUnsafe))
  }

  def extractMessageBytes(length: Int, data: ByteString): SerdeResult[Staging[ByteString]] = {
    if (length < 0) {
      Left(SerdeError.wrongFormat(s"Negative length: $length"))
    } else {
      extractBytes(data, length)
    }
  }

  private def checkMagicBytes(
      data: ByteString
  )(implicit networkConfig: NetworkConfig): SerdeResult[ByteString] = {
    val magicBytes = networkConfig.magicBytes
    if (data.length < magicBytes.length) {
      Left(SerdeError.notEnoughBytes(magicBytes.length, data.length))
    } else {
      Either.cond(
        magicBytes == data.take(magicBytes.length),
        data.drop(magicBytes.length),
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
