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

import org.alephium.util.AlephiumSpec

class Blake3Spec extends AlephiumSpec {
  it should "compare random bytes" in {
    val zeros = Blake3.zero
    Blake3.equals(32, zeros.bytes, 32, zeros.bytes) is true
    (0 until 32).foreach { k =>
      val onlyOne = Array.fill(32)(0.toByte)
      onlyOne(k) = 1.toByte
      Blake3.equals(32, zeros.bytes, 32, ByteString.fromArrayUnsafe(onlyOne)) is false
    }
  }
}
