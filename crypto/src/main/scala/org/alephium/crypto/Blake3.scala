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
import org.bouncycastle.crypto.digests.Blake3Digest

import org.alephium.serde.RandomBytes

class Blake3(val bytes: ByteString) extends RandomBytes {
  def toByte32: Byte32 = Byte32.unsafe(bytes)
}

object Blake3 extends BCHashSchema[Blake3](HashSchema.unsafeBlake3, _.bytes) {
  def length: Int = 32

  def provider(): Blake3Digest = new Blake3Digest(length)
}
