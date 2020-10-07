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

import scala.annotation.tailrec

object Bits {
  def from(byte: Byte): AVector[Boolean] = {
    AVector.tabulate(8) { k =>
      val bit = (byte >> (7 - k)) & 1
      bit == 1
    }
  }

  def toInt(bits: AVector[Boolean]): Int = {
    @tailrec
    def loop(i: Int, acc: Int): Int = {
      if (i == bits.length) acc
      else {
        val newAcc = (acc << 1) + (if (bits(i)) 1 else 0)
        loop(i + 1, newAcc)
      }
    }
    loop(0, 0)
  }
}
