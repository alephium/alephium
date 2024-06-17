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
  def length: Int = Blake3.length

  def toByte32: Byte32 = Byte32.unsafe(bytes)

  override def hashCode(): Int = {
    val length = 32 - 2 // The last two hashes are used for chain index
    (bytes(length - 4) & 0xff) << 24 |
      (bytes(length - 3) & 0xff) << 16 |
      (bytes(length - 2) & 0xff) << 8 |
      (bytes(length - 1) & 0xff)
  }

  override def equals(obj: Any): Boolean =
    obj match {
      case that: Blake3 =>
        Blake3.equals(this.length, this.bytes, that.length, that.bytes)
      case _ => false
    }
}

object Blake3 extends BCHashSchema[Blake3](HashSchema.unsafeBlake3, _.bytes) {
  def length: Int = 32

  def provider(): Blake3Digest = new Blake3Digest(length)

  @inline def equals(aLen: Int, a: ByteString, bLen: Int, b: ByteString): Boolean = {
    var equal = aLen == bLen
    if (equal) {
      var index = aLen - 3
      while (index >= 0 && equal) {
        equal = a(index) == b(index)
        index -= 1
      }
    }
    equal && a(aLen - 2) == b(aLen - 2) && a(aLen - 1) == b(aLen - 1)
  }
}
