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

import org.scalatest.Assertion

import org.alephium.flow.FlowFixture
import org.alephium.flow.model.BlockDeps
import org.alephium.protocol.model.{Block, ChainIndex}
import org.alephium.util.{AlephiumSpec, AVector}

class BlockFlowValidationSpec extends AlephiumSpec {
  it should "sort Deps" in new FlowFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    def test(block: Block): Assertion = {
      val bestDep     = block.header.blockDeps.max(blockFlow.blockHashOrdering)
      val blockDeps   = BlockDeps(block.header.blockDeps)
      val targetGroup = block.chainIndex.from
      val result      = BlockFlowValidation.sortDeps(blockDeps, bestDep, targetGroup)
      result.take(groups0).map(hash => ChainIndex.from(hash)) is
        AVector.from((0 until groups0).map(ChainIndex.unsafe(targetGroup.value, _)))
      result.takeRight(groups0 - 1).map(hash => ChainIndex.from(hash).from.value) is
        AVector.from((0 until groups0).filter(_ != targetGroup.value))
    }

    val newBlocks = for {
      i <- 0 to 1
      j <- 0 to 1
    } yield transferOnlyForIntraGroup(blockFlow, ChainIndex.unsafe(i, j))
    newBlocks.foreach { block =>
      addAndCheck(blockFlow, block, 1)
      blockFlow.getWeight(block) isE consensusConfig.maxMiningTarget * 1
      test(block)
    }
  }
}
