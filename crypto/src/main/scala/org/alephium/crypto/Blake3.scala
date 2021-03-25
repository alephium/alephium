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
import io.github.rctcwyvrn.blake3.{Blake3 => Blake3Java}

import org.alephium.serde.RandomBytes

class Blake3(val bytes: ByteString) extends RandomBytes {
  def toByte32: Byte32 = Byte32.unsafe(bytes)
}

object Blake3 extends HashSchema[Blake3](HashSchema.unsafeBlake3, _.bytes) {
  def length: Int = 32

  def hash(input: Seq[Byte]): Blake3 = {
    val hasher = Blake3Java.newInstance() // For Thread-safety
    hasher.update(input.toArray)
    val res = hasher.digest()
    unsafe(ByteString.fromArrayUnsafe(res))
  }
}
