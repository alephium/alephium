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

import scala.collection.mutable.BitSet

import akka.util.ByteString

class BloomFilter(numberOfBits: Long, numberOfHashes: Int) {
  private var bitCount: Long = 0
  private val bitSet: BitSet = BitSet(0)

  def add(bs: ByteString): Unit = {
    add(MurmurHash.hash(bs.toArray))
  }

  def add(hash: Long): Unit = {
    val hash1 = hash >>> 32
    val hash2 = (hash << 32) >> 32

    var i = 0
    while (i < numberOfHashes) {
      val computedHash = hash1 + i * hash2
      setBits((computedHash & Long.MaxValue) % numberOfBits)
      i += 1
    }
  }

  def mightContain(bs: ByteString): Boolean = {
    mightContain(MurmurHash.hash(bs.toArray))
  }

  def mightContain(hash: Long): Boolean = {
    val hash1 = hash >>> 32
    val hash2 = (hash << 32) >> 32

    var i        = 0
    var continue = true
    var result   = true
    while (i < numberOfHashes && continue) {
      val computedHash = hash1 + i * hash2
      if (!getBits((computedHash & Long.MaxValue) % numberOfBits)) {
        continue = false
        result = false
      }
      i += 1
    }
    result
  }

  def expectedFalsePositiveRate(): Double = {
    math.pow(bitCount.toDouble / numberOfBits, numberOfHashes.toDouble)
  }

  private def getBits(index: Long): Boolean = {
    val higherBits: Int = (index >>> 32).toInt
    val lowerBits: Int  = index.toInt
    bitSet.contains(higherBits) && bitSet.contains(lowerBits)
  }

  private def setBits(index: Long): Unit = {
    val higherBits: Int = (index >>> 32).toInt
    val lowerBits: Int  = index.toInt
    bitSet.add(higherBits)
    bitSet.add(lowerBits)
    bitCount += 1
  }
}

object BloomFilter {
  def apply(numberOfItems: Long, falsePositiveRate: Double): BloomFilter = {
    val nb = optimalNumberOfBits(numberOfItems, falsePositiveRate)
    val nh = optimalNumberOfHashes(numberOfItems, nb)
    new BloomFilter(nb, nh)
  }

  def optimalNumberOfBits(numberOfItems: Long, falsePositiveRate: Double): Long = {
    math.ceil(-1 * numberOfItems * math.log(falsePositiveRate) / math.log(2) / math.log(2)).toLong
  }

  def optimalNumberOfHashes(numberOfItems: Long, numberOfBits: Long): Int = {
    math.ceil(numberOfBits / numberOfItems * math.log(2)).toInt
  }
}
