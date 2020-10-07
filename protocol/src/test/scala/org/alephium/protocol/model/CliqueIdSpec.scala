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

import org.alephium.util.AlephiumSpec

class CliqueIdSpec extends AlephiumSpec with NoIndexModelGenerators {
  it should "compute hamming distance for peer ids" in {
    forAll(cliqueIdGen, cliqueIdGen) { (id0, id1) =>
      val output0 = CliqueId.hammingDist(id0, id1)
      val output1 = id0.hammingDist(id1)
      val output2 = id1.hammingDist(id0)
      val expected =
        (0 until CliqueId.length).map { i =>
          val byte0 = id0.bytes(i) & 0xFF
          val byte1 = id1.bytes(i) & 0xFF
          Integer.bitCount(byte0 ^ byte1)
        }.sum

      output0 is expected
      output1 is expected
      output2 is expected
    }
  }

  it should "compute hamming distance for bytes" in {
    forAll { (byte0: Byte, byte1: Byte) =>
      var xor      = byte0 ^ byte1
      var distance = 0
      (0 until 8) foreach { _ =>
        if (xor % 2 != 0) distance += 1
        xor = xor >> 1
      }
      CliqueId.hammingDist(byte0, byte1) is distance
    }
  }
}
