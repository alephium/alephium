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

import org.alephium.serde._
import org.alephium.util.{Bytes, DjbHash, Hex}

final case class Checksum private (bytes: ByteString) extends AnyVal {
  def check(data: ByteString): SerdeResult[Unit] = {
    val expected = Checksum.calc(data).bytes
    Either.cond(
      bytes == expected,
      (),
      SerdeError.wrongFormat(
        s"Wrong checksum: expected ${Hex.toHexString(expected)}, got ${Hex.toHexString(bytes)}"
      )
    )
  }

  def toHexString: String = Hex.toHexString(bytes)
}

object Checksum {
  private[protocol] val checksumLength: Int = 4

  implicit val serde: Serde[Checksum] = new Serde[Checksum] {
    override def serialize(input: Checksum): ByteString = input.bytes

    override def _deserialize(input: ByteString): SerdeResult[Staging[Checksum]] =
      extractBytes(input, checksumLength).map(_.mapValue(unsafe))
  }

  def calc(bytes: ByteString): Checksum = {
    unsafe(Bytes.from(DjbHash.intHash(bytes)))
  }

  def unsafe(bytes: ByteString): Checksum = {
    assume(bytes.length == checksumLength)
    Checksum(bytes)
  }
}
