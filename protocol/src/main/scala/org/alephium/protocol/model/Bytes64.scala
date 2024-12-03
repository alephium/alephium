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

import org.alephium.crypto.SecP256K1Signature
import org.alephium.serde.Serde

final case class Bytes64 private (bytes: ByteString) extends AnyVal {
  def toSecP256K1Signature: SecP256K1Signature = SecP256K1Signature.unsafe(bytes)
}

object Bytes64 {
  val length: Int = 64

  def from(bytes: ByteString): Option[Bytes64] = {
    if (bytes.length == length) Some(Bytes64(bytes)) else None
  }

  def from(signature: SecP256K1Signature): Bytes64 = Bytes64(signature.bytes)

  implicit val serde: Serde[Bytes64] = Serde.bytesSerde(length).xmap(Bytes64.apply, _.bytes)
}
