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

import org.alephium.flow.FlowFixture
import org.alephium.protocol.model.{ChainIndex, GroupIndex}
import org.alephium.util.AlephiumSpec

class BlockValidationSpec extends AlephiumSpec {
  it should "calculate all the hashes for double spending check when there are genesis deps" in new FlowFixture {
    val block0 = emptyBlock(blockFlow, ChainIndex.unsafe(0, 1))
    addAndCheck(blockFlow, block0)

    val blockFlow1 = isolatedBlockFlow()
    val block1     = emptyBlock(blockFlow1, ChainIndex.unsafe(0, 0))
    addAndCheck(blockFlow1, block1)
    val block2 = emptyBlock(blockFlow1, ChainIndex.unsafe(0, 0))
    addAndCheck(blockFlow1, block2)
    val block3 = emptyBlock(blockFlow1, ChainIndex.unsafe(0, 2))
    addAndCheck(blockFlow1, block3)

    addAndCheck(blockFlow, block1)
    addAndCheck(blockFlow, block2)
    addAndCheck(blockFlow, block3)

    val mainGroup = GroupIndex.unsafe(0)
    val bestDeps  = blockFlow.getBestDeps(mainGroup)
    blockFlow.getHashesForDoubleSpendingCheckUnsafe(mainGroup, bestDeps).toSet is
      Set(block0.hash, block1.hash, block2.hash, block3.hash)
    val bestDeps1 = blockFlow1.getBestDeps(mainGroup)
    blockFlow1.getHashesForDoubleSpendingCheckUnsafe(mainGroup, bestDeps1).toSet is
      Set(block1.hash, block2.hash, block3.hash)
  }

  it should "calculate all the hashes for double spending check when there are no genesis deps" in new FlowFixture {
    val blockFlow1 = isolatedBlockFlow()
    val mainGroup  = GroupIndex.unsafe(0)
    (0 until groups0).reverse.foreach { toGroup =>
      val chainIndex = ChainIndex.unsafe(mainGroup.value, toGroup)
      val block      = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, block)
      addAndCheck(blockFlow1, block)
    }

    val block0 = emptyBlock(blockFlow, ChainIndex.unsafe(0, 1))
    addAndCheck(blockFlow, block0)
    val block1 = emptyBlock(blockFlow1, ChainIndex.unsafe(0, 0))
    addAndCheck(blockFlow, block1)
    val block2 = emptyBlock(blockFlow1, ChainIndex.unsafe(0, 2))
    addAndCheck(blockFlow, block2)

    val bestDeps = blockFlow.getBestDeps(mainGroup)
    blockFlow.getHashesForDoubleSpendingCheckUnsafe(mainGroup, bestDeps).toSet is
      Set(block0.hash, block1.hash, block2.hash)
  }
}
