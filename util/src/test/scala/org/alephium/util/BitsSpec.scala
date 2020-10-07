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

package org.alephium.util

class BitsSpec extends AlephiumSpec {
  it should "convert for special cases" in {
    val zero = AVector.fill(8)(false)

    Bits.from(0) is zero
    Bits.from(-1) is AVector.fill(8)(true)
    0 until 8 foreach { k =>
      Bits.from((1 << k).toByte) is zero.replace(7 - k, true)
    }

    Bits.toInt(zero) is 0
    Bits.toInt(AVector.fill(10)(true)) is 1023
  }

  it should "convert general byte" in {
    forAll { byte: Byte =>
      Bits.toInt(Bits.from(byte)) is Bytes.toPosInt(byte)
    }
  }
}
