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
import org.alephium.protocol.ALPH
import org.alephium.protocol.model.{BlockHash, ChainIndex, GroupIndex}
import org.alephium.util.{AlephiumSpec, AVector, Duration, TimeStamp}

class FlowTipsUtilSpec extends AlephiumSpec {
  trait Fixture extends FlowFixture {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))
  }

  it should "compute light tips for genesis" in new Fixture {
    for {
      from   <- 0 until groups0
      to     <- 0 until groups0
      target <- 0 until groups0
    } {
      val genesis = blockFlow.genesisBlocks(from)(to).header

      if ((from equals to) || (from equals target)) {
        val lightTips = blockFlow.getLightTipsUnsafe(genesis, GroupIndex.unsafe(target))
        lightTips.inTips.toSeq is (0 until groups0)
          .filter(_ != target)
          .map(blockFlow.initialGenesisHashes.apply)
        lightTips.outTip is blockFlow.initialGenesisHashes(target)
      } else {
        assertThrows[AssertionError](
          blockFlow.getLightTipsUnsafe(genesis, GroupIndex.unsafe(target))
        )
      }
    }
  }

  it should "compute light tips for new blocks" in new Fixture {
    val newBlocks = IndexedSeq.tabulate(groups0, groups0, groups0) { case (from, to, target) =>
      val chainIndex = ChainIndex.unsafe(from, to)
      val block      = emptyBlock(blockFlow, chainIndex)

      if ((from equals to) || (from equals target)) {
        val lightTips = blockFlow.getLightTipsUnsafe(block.header, GroupIndex.unsafe(target))
        lightTips.inTips.toSeq is (0 until groups0)
          .filter(_ != target)
          .map { k => if (k equals from) block.hash else blockFlow.initialGenesisHashes(k) }
        lightTips.outTip is
          (if (target equals from) block.hash else blockFlow.initialGenesisHashes(target))
      }

      block
    }

    newBlocks.foreach(_.foreach(_.foreach(addAndCheck(blockFlow, _))))
    val bestNewHashes = Seq.tabulate(groups0) { g =>
      newBlocks(g)(g).map(_.hash).max(blockFlow.blockHashOrdering)
    }

    for {
      from   <- 0 until groups0
      to     <- 0 until groups0
      target <- 0 until groups0
    } {
      val chainIndex = ChainIndex.unsafe(from, to)
      val block      = emptyBlock(blockFlow, chainIndex)

      if ((from equals to) || (from equals target)) {
        val lightTips = blockFlow.getLightTipsUnsafe(block.header, GroupIndex.unsafe(target))
        lightTips.inTips.toSeq is (0 until groups0)
          .filter(_ != target)
          .map { k => if (k equals from) block.hash else bestNewHashes(k) }
        lightTips.outTip is (if (target equals from) block.hash else bestNewHashes(target))
      }
    }
  }

  it should "compute flow tips for genesis" in new Fixture {
    for {
      from   <- 0 until groups0
      to     <- 0 until groups0
      target <- 0 until groups0
    } {
      val genesis = blockFlow.genesisBlocks(from)(to).header

      if ((from equals to) || (from equals target)) {
        val flowTips = blockFlow.getFlowTipsUnsafe(genesis, GroupIndex.unsafe(target))
        flowTips.targetGroup.value is target
        flowTips.inTips.toSeq is (0 until groups0)
          .filter(_ != target)
          .map(blockFlow.initialGenesisHashes.apply)
        flowTips.outTips is blockFlow.genesisBlocks(target).map(_.hash)
      } else {
        assertThrows[AssertionError](
          blockFlow.getFlowTipsUnsafe(genesis, GroupIndex.unsafe(target))
        )
      }
    }
  }

  it should "compute flow tips for new blocks" in new Fixture {
    val newBlocks0 = IndexedSeq.tabulate(groups0, groups0, groups0) { case (from, to, _) =>
      val chainIndex = ChainIndex.unsafe(from, to)
      emptyBlock(blockFlow, chainIndex)
    }
    newBlocks0.foreach(_.foreach(_.foreach(addAndCheck(blockFlow, _))))
    val bestNewHashes0 = Seq.tabulate(groups0) { g =>
      newBlocks0(g)(g).map(_.hash).max(blockFlow.blockHashOrdering)
    }

    for {
      from   <- 0 until groups0
      to     <- 0 until groups0
      target <- 0 until groups0
    } yield {
      val block = newBlocks0(from)(to)(target)

      if ((from equals to) || (from equals target)) {
        val flowTips = blockFlow.getFlowTipsUnsafe(block.header, GroupIndex.unsafe(target))
        block.chainIndex is ChainIndex.unsafe(from, to)
        flowTips.inTips.toSeq is (0 until groups0)
          .filter(_ != target)
          .map { k => if (k equals from) block.hash else blockFlow.initialGenesisHashes(k) }
        val outTipsExpected = if (target equals from) {
          block.header.outTips
        } else {
          blockFlow.genesisBlocks(target).map(_.hash)
        }
        flowTips.outTips is outTipsExpected
      }
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
      val block = newBlocks1(from * groups0 * groups0 + to * groups0 + target)

      block.chainIndex is ChainIndex.unsafe(from, to)
      if ((from equals to) || (from equals target)) {
        val flowTips = blockFlow.getFlowTipsUnsafe(block.header, GroupIndex.unsafe(target))
        flowTips.inTips.toSeq is (0 until groups0)
          .filter(_ != target)
          .map { k => if (k equals from) block.hash else bestNewHashes0(k) }
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

  it should "detect tx conflicts" in new FlowFixture {
    val (genesisPriKey, _, _) = genesisKeys(0)
    val block      = transfer(blockFlow, genesisPriKey, genesisPriKey.publicKey, ALPH.alph(10))
    val blockFlow1 = isolatedBlockFlow()
    addAndCheck(blockFlow, block)
    addAndCheck(blockFlow1, block)

    val block0 = transfer(blockFlow, ChainIndex.unsafe(0, 1))
    val block1 = transfer(blockFlow1, ChainIndex.unsafe(0, 2))
    addAndCheck(blockFlow, block0)

    val block2 = transfer(blockFlow, ChainIndex.unsafe(0, 1))
    addAndCheck(blockFlow, block1, block2)

    val block3 = emptyBlock(blockFlow, ChainIndex.unsafe(0, 0))
    Set(block1.hash, block2.hash).intersect(block3.blockDeps.deps.toSet).size is 1
    addAndCheck(blockFlow, block3)
  }

  it should "use proper timestamp" in {
    val currentTs = TimeStamp.now()
    val pastTs    = currentTs.minusUnsafe(Duration.ofHoursUnsafe(1))
    val futureTs  = currentTs.plusHoursUnsafe(1)

    Thread.sleep(10) // wait until TimStamp.now() > currentTs
    FlowUtils.nextTimeStamp(pastTs) > currentTs is true
    FlowUtils.nextTimeStamp(currentTs) > currentTs is true
    FlowUtils.nextTimeStamp(futureTs) is futureTs.plusMillisUnsafe(1)
  }

  it should "calculate proper groupTips" in new FlowFixture {
    blockFlow.genesisBlocks.foreach {
      _.foreach { block =>
        (0 until groups0).foreach { g =>
          blockFlow.getGroupTip(block.header, GroupIndex.unsafe(g)) is
            blockFlow.genesisBlocks(g)(g).hash
        }
      }
    }

    val block = emptyBlock(blockFlow, ChainIndex.unsafe(0, 1))
    blockFlow.getGroupTip(block.header, GroupIndex.unsafe(0)) is block.header.hash
  }

  it should "calculate incoming blocks for a target group" in new Fixture {
    val chainIndex0      = ChainIndex.unsafe(0, 0)
    val targetGroupIndex = GroupIndex.unsafe(1)

    val block0 = transfer(blockFlow, chainIndex0)
    addAndCheck(blockFlow, block0)
    val bestDeps0 = blockFlow.getBestDeps(targetGroupIndex)
    bestDeps0.deps.contains(block0.hash) is true
    blockFlow.getIncomingBlockDeps(targetGroupIndex, bestDeps0).rightValue.isEmpty is true

    var newBlocks = AVector.empty[BlockHash]
    brokerConfig.cliqueGroupIndexes.filter(_ != targetGroupIndex).foreach { fromGroupIndex =>
      val chainIndex = ChainIndex(fromGroupIndex, targetGroupIndex)
      val block      = transfer(blockFlow, chainIndex)
      addAndCheck(blockFlow, block)
      newBlocks = newBlocks :+ block.hash
      val bestDeps = blockFlow.getBestDeps(targetGroupIndex)
      bestDeps.deps.contains(block.hash) is false
      blockFlow.getIncomingBlockDeps(targetGroupIndex, bestDeps0) isE newBlocks
    }
  }

  it should "get genesis block flow skeleton" in new Fixture {
    val intraHashes = brokerConfig.cliqueGroups.map(g => blockFlow.genesisHashes(g.value)(g.value))
    brokerConfig.cliqueGroups.foreach { group =>
      val intraHash = intraHashes(group.value)
      val skeleton  = blockFlow.getBlockFlowSkeletonUnsafe(intraHash)
      skeleton.intraGroupTips is intraHashes
      skeleton.intraGroupTipOutTips is blockFlow.genesisHashes
    }
  }

  it should "get block flow skeleton" in new Fixture {
    val blocks = AVector.tabulate(brokerConfig.groups, brokerConfig.groups) { case (from, to) =>
      val chainIndex = ChainIndex.unsafe(from, to)
      val block      = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, block)
      block
    }
    val intraHashes = brokerConfig.cliqueGroups.map(g => blocks(g.value)(g.value).hash)
    intraHashes.length is 3

    val genesisHashes = blockFlow.genesisHashes
    val skeleton0     = blockFlow.getBlockFlowSkeletonUnsafe(intraHashes(0))
    skeleton0.intraGroupTips is AVector(intraHashes(0), genesisHashes(1)(1), genesisHashes(2)(2))
    skeleton0.intraGroupTipOutTips is AVector(
      genesisHashes(0).replace(0, blocks(0)(0).hash),
      genesisHashes(1),
      genesisHashes(2)
    )

    val skeleton1 = blockFlow.getBlockFlowSkeletonUnsafe(intraHashes(1))
    skeleton1.intraGroupTips is AVector(intraHashes(0), intraHashes(1), genesisHashes(2)(2))
    skeleton1.intraGroupTipOutTips is AVector(
      genesisHashes(0).replace(0, blocks(0)(0).hash),
      blocks(1).map(_.hash).replace(2, genesisHashes(1)(2)),
      genesisHashes(2)
    )

    val skeleton2 = blockFlow.getBlockFlowSkeletonUnsafe(intraHashes(2))
    skeleton2.intraGroupTips is intraHashes
    skeleton2.intraGroupTipOutTips is AVector(
      genesisHashes(0).replace(0, blocks(0)(0).hash),
      blocks(1).map(_.hash).replace(2, genesisHashes(1)(2)),
      blocks(2).map(_.hash)
    )
  }

  it should "extend block flow skeleton" in new Fixture {
    val chainIndex0                    = ChainIndex.unsafe(0, 0)
    val (blockAtH1G0_0, blockAtH1G0_1) = mineTwoBlocksAndAdd(chainIndex0)
    val blockAtH2G0_0                  = emptyBlock(blockFlow, chainIndex0)

    val chainIndex2                    = ChainIndex.unsafe(2, 2)
    val (blockAtH1G2_0, blockAtH1G2_1) = mineTwoBlocksAndAdd(chainIndex2)

    val blockAtH2G0_1 = emptyBlock(blockFlow, chainIndex0)
    val blockAtH2G0_2 = mineBlockWithDep(chainIndex0, blockAtH1G2_1.hash)

    val blockAtH2G2 = emptyBlock(blockFlow, chainIndex2)
    addAndCheck(blockFlow, blockAtH2G2)

    val genesisHashes = blockFlow.genesisHashes
    val blockAtH2G0_3 = emptyBlock(blockFlow, chainIndex0)
    blockAtH2G0_0.blockDeps.inDeps(1) is genesisHashes(2)(2)
    blockAtH2G0_1.blockDeps.inDeps(1) is blockAtH1G2_0.hash
    blockAtH2G0_2.blockDeps.inDeps(1) is blockAtH1G2_1.hash
    blockAtH2G0_3.blockDeps.inDeps(1) is blockAtH2G2.hash
    addAndCheck(blockFlow, blockAtH2G0_0, blockAtH2G0_1, blockAtH2G0_2, blockAtH2G0_3)

    val skeleton = blockFlow.getBlockFlowSkeletonUnsafe(blockAtH1G2_0.hash)
    skeleton.intraGroupTips is AVector(blockAtH1G0_0.hash, genesisHashes(1)(1), blockAtH1G2_0.hash)
    skeleton.intraGroupTipOutTips is AVector(
      genesisHashes(0).replace(0, blockAtH1G0_0.hash),
      genesisHashes(1),
      genesisHashes(2).replace(2, blockAtH1G2_0.hash)
    )

    val group0 = chainIndex0.from
    blockFlow.tryExtendBlockFlowSkeletonUnsafe(skeleton, group0, genesisHashes(0)(0)) is None
    blockFlow.tryExtendBlockFlowSkeletonUnsafe(skeleton, group0, blockAtH1G0_0.hash) is None
    blockFlow.tryExtendBlockFlowSkeletonUnsafe(skeleton, group0, blockAtH1G0_1.hash) is None

    blockFlow.tryExtendBlockFlowSkeletonUnsafe(skeleton, group0, blockAtH2G0_0.hash) is Some(
      BlockFlowSkeleton(
        AVector(blockAtH2G0_0.hash, genesisHashes(1)(1), blockAtH1G2_0.hash),
        AVector(
          genesisHashes(0).replace(0, blockAtH2G0_0.hash),
          genesisHashes(1),
          genesisHashes(2).replace(2, blockAtH1G2_0.hash)
        )
      )
    )
    blockFlow.tryExtendBlockFlowSkeletonUnsafe(skeleton, group0, blockAtH2G0_1.hash) is Some(
      BlockFlowSkeleton(
        AVector(blockAtH2G0_1.hash, genesisHashes(1)(1), blockAtH1G2_0.hash),
        AVector(
          genesisHashes(0).replace(0, blockAtH2G0_1.hash),
          genesisHashes(1),
          genesisHashes(2).replace(2, blockAtH1G2_0.hash)
        )
      )
    )
    blockFlow.tryExtendBlockFlowSkeletonUnsafe(skeleton, group0, blockAtH2G0_2.hash) is None
    blockFlow.tryExtendBlockFlowSkeletonUnsafe(skeleton, group0, blockAtH2G0_3.hash) is Some(
      BlockFlowSkeleton(
        AVector(blockAtH2G0_3.hash, genesisHashes(1)(1), blockAtH2G2.hash),
        AVector(
          genesisHashes(0).replace(0, blockAtH2G0_3.hash),
          genesisHashes(1),
          genesisHashes(2).replace(2, blockAtH2G2.hash)
        )
      )
    )
  }
}
