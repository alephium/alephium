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

package org.alephium.flow.core

import akka.util.ByteString

import org.alephium.protocol.model.{BlockHash, Weight}
import org.alephium.util.AlephiumSpec

class BlockHashPoolSpec extends AlephiumSpec {
  val hash0 = BlockHash.unsafe(ByteString.fromArray(Array.fill(BlockHash.length)(0)))
  val hash1 = BlockHash.unsafe(hash0.bytes.init ++ ByteString(1))

  it should "compare hashes w.r.t. weight" in {
    val weight0 = Weight(0)
    val weight1 = Weight(1)
    BlockHashPool.compareWeight(hash0, weight0, hash0, weight0) is 0
    BlockHashPool.compareWeight(hash0, weight0, hash0, weight1) is -1
    BlockHashPool.compareWeight(hash0, weight1, hash0, weight0) is 1
    BlockHashPool.compareWeight(hash0, weight0, hash1, weight0) is -1
    BlockHashPool.compareWeight(hash1, weight0, hash0, weight0) is 1
  }

  it should "compare hashes w.r.t. height" in {
    val height0 = 0
    val height1 = 1
    BlockHashPool.compareHeight(hash0, height0, hash0, height0) is 0
    BlockHashPool.compareHeight(hash0, height0, hash0, height1) is -1
    BlockHashPool.compareHeight(hash0, height1, hash0, height0) is 1
    BlockHashPool.compareHeight(hash0, height0, hash1, height0) is -1
    BlockHashPool.compareHeight(hash1, height0, hash0, height0) is 1
  }
}
