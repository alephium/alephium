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

package org.alephium.crypto

import akka.util.ByteString

import org.alephium.serde.Serde

final case class Byte64 private (bytes: ByteString) extends AnyVal {
  def toSecP256K1Signature: SecP256K1Signature = SecP256K1Signature.unsafe(bytes)
  def toSecP256R1Signature: SecP256R1Signature = SecP256R1Signature.unsafe(bytes)
  def toED25519Signature: ED25519Signature     = ED25519Signature.unsafe(bytes)
}

object Byte64 {
  val length: Int = 64

  def from(bytes: ByteString): Option[Byte64] = {
    if (bytes.length == length) Some(Byte64(bytes)) else None
  }

  def from(signature: SecP256K1Signature): Byte64 = Byte64(signature.bytes)
  def from(signature: SecP256R1Signature): Byte64 = Byte64(signature.bytes)
  def from(signature: ED25519Signature): Byte64   = Byte64(signature.bytes)

  implicit val serde: Serde[Byte64] = Serde.bytesSerde(length).xmap(Byte64.apply, _.bytes)
}
