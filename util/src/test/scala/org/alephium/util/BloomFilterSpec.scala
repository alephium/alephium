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

import scala.util.Random

import akka.util.ByteString

class BloomFilterSpec extends AlephiumSpec {
  it should "check membership of hashes" in {
    val falsePositiveRate = 0.01
    val filter            = BloomFilter(1000000L, falsePositiveRate)
    val hashes = (1 to 500000).map { _ =>
      val hash = randomHash()
      filter.add(hash)
      hash
    }
    hashes.foreach(filter.mightContain(_) is true)

    val testTotal      = 5000
    var falsePositives = 0.0
    (1 to testTotal).foreach { _ =>
      val hash = randomHash()
      if (filter.mightContain(hash)) {
        falsePositives += 1
      }
    }

    (falsePositives / testTotal) should be <= falsePositiveRate
  }

  def randomHash(): ByteString = {
    val bytes = new Array[Byte](32)
    Random.nextBytes(bytes)
    ByteString(bytes)
  }
}
