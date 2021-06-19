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

import org.alephium.serde.Serde
import org.alephium.util.{SecureAndSlowRandom, UnsecureRandom}

final case class Nonce private (value: ByteString) extends AnyVal

object Nonce {
  val byteLength: Int = 24

  val zero: Nonce = unsafe(ByteString.fromArrayUnsafe(Array.fill(byteLength)(0)))

  implicit val serde: Serde[Nonce] = Serde.bytesSerde(byteLength).xmap(unsafe, _.value)

  def unsafe(bytes: ByteString): Nonce = new Nonce(bytes)

  def from(bytes: ByteString): Option[Nonce] =
    Option.when(bytes.length == byteLength)(unsafe(bytes))

  def unsecureRandom(): Nonce = {
    val bytes = Array.ofDim[Byte](byteLength)
    UnsecureRandom.source.nextBytes(bytes)
    unsafe(ByteString.fromArrayUnsafe(bytes))
  }

  def secureRandom(): Nonce = {
    val bytes = Array.ofDim[Byte](byteLength)
    SecureAndSlowRandom.source.nextBytes(bytes)
    unsafe(ByteString.fromArrayUnsafe(bytes))
  }
}
