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
import org.alephium.protocol.model.{ChainIndex, Target}
import org.alephium.protocol.vm.LockupScript
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

  behavior of "Leman DAA"

  it should "calculate the same target for different groups" in new LemanDifficultyFixture {
    checkTemplates(Some(target => { target is consensusConfig.maxMiningTarget; () }))

    prepareBlocks(Range(2, 100).iterator.next())
    checkTemplates(Some(target => {
      (BigInt(target.value) < BigInt(consensusConfig.maxMiningTarget.value) / 2) is true
      ()
    }))
  }

  it should "calculate common intra deps" in new LemanDifficultyFixture {
    prepareBlocks(2)
    (ALPH.GenesisHeight + 1 until ALPH.GenesisHeight + consensusConfig.powAveragingWindow + 2)
      .foreach { height =>
        brokerConfig.cliqueChainIndexes.foreach { index =>
          val hash      = blockFlow.getBlockChain(index).getHashesUnsafe(height).head
          val blockDeps = blockFlow.getBlockHeaderUnsafe(hash).blockDeps
          val intraDeps = blockFlow.calCommonIntraGroupDepsUnsafe(blockDeps, index.from)
          if (height < ALPH.GenesisHeight + 2) {
            intraDeps is brokerConfig.cliqueGroupIndexes
              .map(group => blockFlow.genesisHashes(group.value)(group.value))
          } else {
            intraDeps is brokerConfig.cliqueChainIndexes
              .filter(_.isIntraGroup)
              .map(blockFlow.getBlockChain(_).getHashesUnsafe(height - 2).head)
          }
        }
      }
  }

  it should "calculate common intra deps when one intra chain does not make progress" in new LemanDifficultyFixture {
    (0 until 4).foreach { _ =>
      brokerConfig.cliqueChainIndexes.foreach { index =>
        if (index != ChainIndex.unsafe(0, 0)) {
          addAndCheck(blockFlow, emptyBlock(blockFlow, index))
        }
      }
    }
    brokerConfig.cliqueGroupIndexes.foreach { index =>
      val blockDeps = blockFlow.getBestDeps(index)
      val intraDeps = blockFlow.calCommonIntraGroupDepsUnsafe(blockDeps, index)
      intraDeps is brokerConfig.cliqueGroupIndexes
        .map(group => blockFlow.genesisHashes(group.value)(group.value))
    }
  }

  it should "calculate diff and time span" in new LemanDifficultyFixture {
    val startTs = TimeStamp.now()
    prepareBlocks(2)
    (ALPH.GenesisHeight until ALPH.GenesisHeight + consensusConfig.powAveragingWindow + 1).foreach {
      height =>
        brokerConfig.cliqueChainIndexes.foreach { index =>
          val hash = blockFlow.getBlockChain(index).getHashesUnsafe(height).head
          blockFlow.getDiffAndTimeSpanUnsafe(hash) is
            (consensusConfig.minMiningDiff,
            consensusConfig.expectedWindowTimeSpan)

          if (index.isIntraGroup) {
            blockFlow.getDiffAndTimeSpanForIntraDepUnsafe(hash) is
              (consensusConfig.minMiningDiff.times(brokerConfig.groups),
              consensusConfig.expectedWindowTimeSpan.timesUnsafe(brokerConfig.groups.toLong))
          }

          blockFlow.getDiffAndTimeSpanUnsafe(
            blockFlow.getBlockHeaderUnsafe(hash).blockDeps.unorderedIntraDeps(index.from)
          ) is
            (consensusConfig.minMiningDiff.times(brokerConfig.chainNum),
            consensusConfig.expectedWindowTimeSpan.timesUnsafe(brokerConfig.chainNum.toLong))
        }
    }
    brokerConfig.cliqueChainIndexes.foreach { index =>
      val hash = blockFlow
        .getBlockChain(index)
        .getHashesUnsafe(ALPH.GenesisHeight + consensusConfig.powAveragingWindow + 1)
        .head
      val (diff, timeSpan) = blockFlow.getDiffAndTimeSpanUnsafe(hash)
      diff is consensusConfig.minMiningDiff
      (timeSpan <= TimeStamp.now().deltaUnsafe(startTs)) is true

      if (index.isIntraGroup) {
        val (diffSum, timeSpanSum) = blockFlow.getDiffAndTimeSpanForIntraDepUnsafe(hash)
        diffSum is consensusConfig.minMiningDiff.times(brokerConfig.groups)
        (timeSpanSum <= TimeStamp
          .now()
          .deltaUnsafe(startTs)
          .timesUnsafe(brokerConfig.groups.toLong)) is false
      }

      val (diffSum, timeSpanSum) = blockFlow.getDiffAndTimeSpanUnsafe(
        blockFlow.getBlockHeaderUnsafe(hash).blockDeps.unorderedIntraDeps(index.from)
      )
      diffSum is consensusConfig.minMiningDiff.times(brokerConfig.chainNum)
      (timeSpanSum <= TimeStamp
        .now()
        .deltaUnsafe(startTs)
        .timesUnsafe(brokerConfig.chainNum.toLong)) is false
    }
    brokerConfig.cliqueChainIndexes.foreach { index =>
      val hash = blockFlow
        .getBlockChain(index)
        .getHashesUnsafe(ALPH.GenesisHeight + consensusConfig.powAveragingWindow + 2)
        .head
      val (diff, timeSpan) = blockFlow.getDiffAndTimeSpanUnsafe(hash)
      diff is consensusConfig.minMiningDiff.times(2)
      (timeSpan <= TimeStamp.now().deltaUnsafe(startTs)) is true

      if (index.isIntraGroup) {
        val (diffSum, timeSpanSum) = blockFlow.getDiffAndTimeSpanForIntraDepUnsafe(hash)
        diffSum is consensusConfig.minMiningDiff.times(brokerConfig.groups + 1)
        (timeSpanSum <= TimeStamp
          .now()
          .deltaUnsafe(startTs)
          .timesUnsafe(brokerConfig.groups.toLong)) is true
      }

      val (diffSum, timeSpanSum) = blockFlow.getDiffAndTimeSpanUnsafe(
        blockFlow.getBlockHeaderUnsafe(hash).blockDeps.unorderedIntraDeps(index.from)
      )
      diffSum is consensusConfig.minMiningDiff.times(brokerConfig.chainNum)
      (timeSpanSum <= TimeStamp
        .now()
        .deltaUnsafe(startTs)
        .timesUnsafe(brokerConfig.chainNum.toLong)) is false
    }
    brokerConfig.cliqueGroupIndexes.foreach { groupIndex =>
      val blockDeps              = blockFlow.getBestDeps(groupIndex)
      val intraDeps              = blockFlow.calCommonIntraGroupDepsUnsafe(blockDeps, groupIndex)
      val (diffSum, timeSpanSum) = blockFlow.getDiffAndTimeSpanUnsafe(intraDeps)
      diffSum is consensusConfig.minMiningDiff.times(brokerConfig.chainNum * 2)
      (timeSpanSum <= TimeStamp
        .now()
        .deltaUnsafe(startTs)
        .timesUnsafe(brokerConfig.chainNum.toLong)) is true
    }
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

  trait LemanDifficultyFixture extends FlowFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    config.network.getHardFork(TimeStamp.now()).isLemanEnabled() is true

    def checkTemplates(testTarget: Option[Target => Unit] = None) = {
      val targets = brokerConfig.cliqueChainIndexes.map { index =>
        val minerAddress = LockupScript.p2pkh(index.to.generateKey._2)
        val template     = blockFlow.prepareBlockFlowUnsafe(index, minerAddress)
        template.target
      }
      targets.toSet.size is 1
      testTarget.foreach(test => test(targets.head))
    }

    def prepareBlocks(scale: => Int): Unit = {
      (0 to consensusConfig.powAveragingWindow).foreach { _ =>
        val blocks = brokerConfig.cliqueChainIndexes.map { chainIndex =>
          emptyBlock(blockFlow, chainIndex)
        }
        blocks.foreach(addAndCheck(blockFlow, _))
        checkTemplates(Some(target => {
          target is consensusConfig.minMiningDiff.getTarget(); ()
        }))
      }
      (0 until 3).foreach { _ =>
        val blocks = brokerConfig.cliqueChainIndexes.map { chainIndex =>
          val block     = emptyBlock(blockFlow, chainIndex)
          val newTarget = Target.unsafe(consensusConfig.maxMiningTarget.value.divide(scale))
          chainIndex -> block.copy(header = block.header.copy(target = newTarget))
        }
        blocks.foreach { case (chainIndex, block) =>
          val worldState = if (chainIndex.isIntraGroup) {
            Some(blockFlow.getCachedWorldState(block.blockDeps, chainIndex.from).rightValue)
          } else {
            None
          }
          blockFlow.addAndUpdateView(reMine(blockFlow, chainIndex, block), worldState)
        }
      }
    }
  }
}
