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

class FlowTipsUtilSpec extends AlephiumSpec {
  trait Fixture extends FlowFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))
  }

  it should "compute light tips for genesis" in new Fixture {
    for {
      from   <- 0 until groups0
      to     <- 0 until groups0
      target <- 0 until groups0
    } {
      val genesis   = blockFlow.genesisBlocks(from)(to).header
      val lightTips = blockFlow.getLightTipsUnsafe(genesis, GroupIndex.unsafe(target))
      lightTips.inTips.toSeq is (0 until groups0)
        .filter(_ != target)
        .map(blockFlow.bestGenesisHashes.apply)
      lightTips.outTip is blockFlow.bestGenesisHashes(target)
    }
  }

  it should "compute light tips for new blocks" in new Fixture {
    val newBlocks = for {
      from   <- 0 until groups0
      to     <- 0 until groups0
      target <- 0 until groups0
    } yield {
      val chainIndex = ChainIndex.unsafe(from, to)
      val block      = emptyBlock(blockFlow, chainIndex)
      val lightTips  = blockFlow.getLightTipsUnsafe(block.header, GroupIndex.unsafe(target))

      lightTips.inTips.toSeq is (0 until groups0)
        .filter(_ != target)
        .map { k =>
          if (k equals from) block.hash else blockFlow.bestGenesisHashes(k)
        }
      lightTips.outTip is
        (if (target equals from) block.hash else blockFlow.bestGenesisHashes(target))

      block
    }

    newBlocks.foreach(addAndCheck(blockFlow, _))
    val bestNewHashes =
      newBlocks
        .grouped(groups0 * groups0)
        .map(_.map(_.hash).max(blockFlow.blockHashOrdering))
        .toSeq

    for {
      from   <- 0 until groups0
      to     <- 0 until groups0
      target <- 0 until groups0
    } {
      val chainIndex = ChainIndex.unsafe(from, to)
      val block      = emptyBlock(blockFlow, chainIndex)
      val lightTips  = blockFlow.getLightTipsUnsafe(block.header, GroupIndex.unsafe(target))

      lightTips.inTips.toSeq is (0 until groups0)
        .filter(_ != target)
        .map { k =>
          if (k equals from) block.hash else bestNewHashes(k)
        }
      lightTips.outTip is (if (target equals from) block.hash else bestNewHashes(target))
    }
  }

  it should "compute flow tips for genesis" in new Fixture {
    for {
      from   <- 0 until groups0
      to     <- 0 until groups0
      target <- 0 until groups0
    } {
      val genesis  = blockFlow.genesisBlocks(from)(to).header
      val flowTips = blockFlow.getFlowTipsUnsafe(genesis, GroupIndex.unsafe(target))
      flowTips.targetGroup.value is target
      flowTips.inTips.toSeq is (0 until groups0)
        .filter(_ != target)
        .map(blockFlow.bestGenesisHashes.apply)
      flowTips.outTips is blockFlow.genesisBlocks(target).map(_.hash)
    }
  }

  it should "compute flow tips for new blocks" in new Fixture {
    val newBlocks0 = for {
      from <- 0 until groups0
      to   <- 0 until groups0
      _    <- 0 until groups0
    } yield {
      val chainIndex = ChainIndex.unsafe(from, to)
      emptyBlock(blockFlow, chainIndex)
    }
    newBlocks0.foreach(addAndCheck(blockFlow, _))
    val bestNewHashes0 =
      newBlocks0
        .grouped(groups0 * groups0)
        .map(_.map(_.hash).max(blockFlow.blockHashOrdering))
        .toSeq

    for {
      from   <- 0 until groups0
      to     <- 0 until groups0
      target <- 0 until groups0
    } yield {
      val block    = newBlocks0(from * groups0 * groups0 + to * groups0 + target)
      val flowTips = blockFlow.getFlowTipsUnsafe(block.header, GroupIndex.unsafe(target))

      block.chainIndex is ChainIndex.unsafe(from, to)
      flowTips.inTips.toSeq is (0 until groups0)
        .filter(_ != target)
        .map { k =>
          if (k equals from) block.hash else blockFlow.bestGenesisHashes(k)
        }
      val outTipsExpected = if (target equals from) {
        block.header.outTips
      } else {
        blockFlow.genesisBlocks(target).map(_.hash)
      }
      flowTips.outTips is outTipsExpected
    }

    val newBlocks1 = for {
      from <- 0 until groups0
      to   <- 0 until groups0
      _    <- 0 until groups0
    } yield {
      val chainIndex = ChainIndex.unsafe(from, to)
      emptyBlock(blockFlow, chainIndex)
    }
    newBlocks1.foreach(addAndCheck(blockFlow, _))

    for {
      from   <- 0 until groups0
      to     <- 0 until groups0
      target <- 0 until groups0
    } {
      val block    = newBlocks1(from * groups0 * groups0 + to * groups0 + target)
      val flowTips = blockFlow.getFlowTipsUnsafe(block.header, GroupIndex.unsafe(target))

      block.chainIndex is ChainIndex.unsafe(from, to)
      flowTips.inTips.toSeq is (0 until groups0)
        .filter(_ != target)
        .map { k =>
          if (k equals from) block.hash else bestNewHashes0(k)
        }
      val outTipsExpected = if (target equals from) {
        block.header.outTips
      } else {
        val bestHash       = bestNewHashes0(target)
        val bestChainIndex = ChainIndex.from(bestHash)
        val genesisDeps    = blockFlow.genesisBlocks(target).map(_.hash)
        bestChainIndex.from.value is target
        genesisDeps.replace(bestChainIndex.to.value, bestHash)
      }
      flowTips.outTips is outTipsExpected
    }
  }
}
