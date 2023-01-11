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

class ChainIndexSpec extends AlephiumSpec with NoIndexModelGenerators {
  it should "check when it's intra group index" in {
    val index0 = ChainIndex.unsafe(0, 0)
    val index1 = ChainIndex.unsafe(0, 1)
    val index2 = ChainIndex.unsafe(1, 0)
    val index3 = ChainIndex.unsafe(1, 1)
    index0.isIntraGroup is true
    index1.isIntraGroup is false
    index2.isIntraGroup is false
    index3.isIntraGroup is true
  }

  it should "compute the correct index" in {
    forAll(blockGen) { block =>
      val index = block.chainIndex

      val hash2Int = BigInt(1, block.hash.bytes.takeRight(2).toArray)
      val rawIndex = (hash2Int % groupConfig.chainNum).toInt
      index.from.value is rawIndex / groupConfig.groups
      index.to.value is rawIndex % groupConfig.groups
    }
  }

  it should "equalize same values" in {
    forAll(groupIndexGen, groupIndexGen) { (from, to) =>
      val index1 = ChainIndex(from, to)
      val index2 = ChainIndex(from, to)
      index1 is index2
    }

    val index1 = new ChainIndex(new GroupIndex(999999), new GroupIndex(999999))
    val index2 = new ChainIndex(new GroupIndex(999999), new GroupIndex(999999))
    index1 is index2
  }

  it should "check from group" in {
    ChainIndex.checkFromGroup(ChainIndex.unsafe(0, 1).flattenIndex, GroupIndex.unsafe(0)) is true
    ChainIndex.checkFromGroup(ChainIndex.unsafe(0, 1).flattenIndex, GroupIndex.unsafe(1)) is false
    ChainIndex.checkFromGroup(ChainIndex.unsafe(1, 0).flattenIndex, GroupIndex.unsafe(0)) is false
    ChainIndex.checkFromGroup(ChainIndex.unsafe(1, 1).flattenIndex, GroupIndex.unsafe(1)) is true
  }
}
