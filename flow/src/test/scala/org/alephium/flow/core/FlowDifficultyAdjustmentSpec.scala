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
import org.alephium.protocol.model.{ChainIndex, Target}
import org.alephium.util.{AlephiumSpec, TimeStamp}

class FlowDifficultyAdjustmentSpec extends AlephiumSpec {

  it should "calculate weighted target" in new PreLemanDifficultyFixture {
    prepareBlocks(2)

    val bestDeps = blockFlow.getBestDeps(chainIndex.from)
    val nextTargetRaw = blockFlow
      .getHeaderChain(chainIndex)
      .getNextHashTargetRaw(bestDeps.uncleHash(chainIndex.to), TimeStamp.now())
      .rightValue
      .value
    (BigInt(nextTargetRaw) < BigInt(consensusConfig.maxMiningTarget.value) / 2) is true
    val nextTargetClipped =
      blockFlow.getNextHashTarget(chainIndex, bestDeps, TimeStamp.now()).rightValue
    (nextTargetClipped > Target.unsafe(consensusConfig.maxMiningTarget.value / 2)) is true
  }

  it should "clip target" in new PreLemanDifficultyFixture {
    prepareBlocks(8 * groups0)

    val bestDeps = blockFlow.getBestDeps(chainIndex.from)
    val nextTargetRaw = blockFlow
      .getHeaderChain(chainIndex)
      .getNextHashTargetRaw(bestDeps.uncleHash(chainIndex.to), TimeStamp.now())
      .rightValue
      .value
    (BigInt(nextTargetRaw) < BigInt(consensusConfig.maxMiningTarget.value) / 2) is true
    val nextTargetClipped =
      blockFlow.getNextHashTarget(chainIndex, bestDeps, TimeStamp.now()).rightValue
    nextTargetClipped is Target.unsafe(consensusConfig.maxMiningTarget.value / 2)
  }

  trait PreLemanDifficultyFixture extends FlowFixture {
    override val configValues = Map(
      ("alephium.network.leman-hard-fork-timestamp ", TimeStamp.now().plusHoursUnsafe(1).millis)
    )
    config.network.getHardFork(TimeStamp.now()).isLemanEnabled() is false

    val chainIndex = ChainIndex.unsafe(0, 1)

    def prepareBlocks(scale: Int): Unit = {
      (0 until consensusConfig.powAveragingWindow + 1).foreach { k =>
        val block = emptyBlock(blockFlow, chainIndex)
        // we increase the difficulty for the last block of the DAA window (17 blocks)
        if (k equals consensusConfig.powAveragingWindow) {
          val newTarget = Target.unsafe(consensusConfig.maxMiningTarget.value.divide(scale))
          val newBlock  = block.copy(header = block.header.copy(target = newTarget))
          blockFlow.addAndUpdateView(reMine(blockFlow, chainIndex, newBlock), None)
        } else {
          addAndCheck(blockFlow, block)
          val bestDep = blockFlow.getBestDeps(chainIndex.from)
          blockFlow.getNextHashTarget(
            chainIndex,
            bestDep,
            TimeStamp.now()
          ) isE consensusConfig.maxMiningTarget
        }
      }
    }
  }
}
