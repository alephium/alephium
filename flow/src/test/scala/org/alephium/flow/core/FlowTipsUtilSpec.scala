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
}
