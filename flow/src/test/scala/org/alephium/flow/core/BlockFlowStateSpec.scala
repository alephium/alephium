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
import org.alephium.protocol.model.{Block, ChainIndex, GroupIndex}
import org.alephium.util.AlephiumSpec

class BlockFlowStateSpec extends AlephiumSpec {
  it should "calculate all the hashes for state update" in new FlowFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    def prepare(chainIndex: ChainIndex): Block = {
      val block = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, block)
      block
    }

    val mainGroup = GroupIndex.unsafe(0)
    val block0    = prepare(ChainIndex.unsafe(0, 2))
    val block1    = prepare(ChainIndex.unsafe(1, 0))
    prepare(ChainIndex.unsafe(1, 1))
    val block2 = prepare(ChainIndex.unsafe(0, 1))
    blockFlow.getHashesForUpdates(mainGroup).rightValue.toSet is
      Set(block0.hash, block1.hash, block2.hash)
  }
}
