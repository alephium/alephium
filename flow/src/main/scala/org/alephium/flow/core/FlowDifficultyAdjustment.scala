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

import java.math.BigInteger

import org.alephium.flow.Utils
import org.alephium.flow.setting.{ConsensusSetting, ConsensusSettings}
import org.alephium.io.{IOResult, IOUtils}
import org.alephium.protocol.ALPH
import org.alephium.protocol.config.{BrokerConfig, NetworkConfig}
import org.alephium.protocol.model._
import org.alephium.util.{AVector, Cache, Duration, Math, TimeStamp}

trait FlowDifficultyAdjustment {
  implicit def brokerConfig: BrokerConfig
  def consensusConfigs: ConsensusSettings
  implicit def networkConfig: NetworkConfig

  def genesisHashes: AVector[AVector[BlockHash]]

  def getBlockHeaderUnsafe(hash: BlockHash): BlockHeader
  def getHeightUnsafe(hash: BlockHash): Int
  def getHeaderChain(hash: BlockHash): BlockHeaderChain
  def getHashChain(hash: BlockHash): BlockHashChain

  def getNextHashTarget(
      chainIndex: ChainIndex,
      deps: BlockDeps,
      nextTimeStamp: TimeStamp
  ): IOResult[Target] = {
    val hardFork = networkConfig.getHardFork(nextTimeStamp)
    if (hardFork.isDanubeEnabled()) {
      getNextHashTargetDanube(chainIndex, deps)
    } else if (hardFork.isRhoneEnabled()) {
      getNextHashTargetRhone(chainIndex, deps)
    } else if (hardFork.isLemanEnabled()) {
      getNextHashTargetLeman(chainIndex, deps)
    } else {
      getNextHashTargetGenesis(chainIndex, deps, nextTimeStamp)
    }
  }

  def getNextHashTargetGenesis(
      chainIndex: ChainIndex,
      deps: BlockDeps,
      nextTimeStamp: TimeStamp
  ): IOResult[Target] = {
    for {
      newTarget <- {
        val tip = deps.uncleHash(chainIndex.to)
        getHeaderChain(tip).getNextHashTargetRaw(tip, nextTimeStamp)
      }
      depTargets <- deps.deps.mapE(hash => getHeaderChain(hash).getTarget(hash))
    } yield {
      val weightedTarget = Target.average(newTarget, depTargets)
      val maxTarget      = depTargets.fold(weightedTarget)(Math.max)
      Target.clipByTwoTimes(maxTarget, weightedTarget)
    }
  }

  private def getNextHashTargetSinceLeman(
      chainIndex: ChainIndex,
      deps: BlockDeps,
      hardFork: HardFork
  )(implicit consensusConfig: ConsensusSetting): IOResult[Target] = IOUtils.tryExecute {
    val commonIntraGroupDeps             = calCommonIntraGroupDepsUnsafe(deps, chainIndex.from)
    val (diffSum, timeSpanSum, oldestTs) = getDiffAndTimeSpanUnsafe(commonIntraGroupDeps)
    val diffAverage                      = diffSum.divide(brokerConfig.chainNum)
    val timeSpanAverage                  = timeSpanSum.divUnsafe(brokerConfig.chainNum.toLong)

    val chainDep   = deps.getOutDep(chainIndex.to)
    val heightGap  = calHeightDiffUnsafe(chainDep, oldestTs)
    val targetDiff = consensusConfig.penalizeDiffForHeightGapLeman(diffAverage, heightGap, hardFork)
    ChainDifficultyAdjustment.calNextHashTargetRaw(targetDiff.getTarget(), timeSpanAverage)
  }

  def getNextHashTargetLeman(
      chainIndex: ChainIndex,
      deps: BlockDeps
  ): IOResult[Target] =
    getNextHashTargetSinceLeman(chainIndex, deps, HardFork.Leman)(consensusConfigs.mainnet)

  def getNextHashTargetRhone(
      chainIndex: ChainIndex,
      deps: BlockDeps
  ): IOResult[Target] =
    getNextHashTargetSinceLeman(chainIndex, deps, HardFork.Rhone)(consensusConfigs.rhone)

  def getNextHashTargetDanube(
      chainIndex: ChainIndex,
      deps: BlockDeps
  ): IOResult[Target] =
    getNextHashTargetSinceLeman(chainIndex, deps, HardFork.Danube)(consensusConfigs.danube)

  final def calHeightDiffUnsafe(chainDep: BlockHash, oldTimeStamp: TimeStamp): Int = {
    @scala.annotation.tailrec
    def loop(currentHash: BlockHash, acc: Int): Int = {
      val header = getBlockHeaderUnsafe(currentHash)
      if (header.timestamp <= oldTimeStamp) {
        acc
      } else {
        loop(header.parentHash, acc + 1)
      }
    }

    loop(chainDep, 0)
  }

  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  def calCommonIntraGroupDepsUnsafe(
      deps: BlockDeps,
      mainGroup: GroupIndex
  ): AVector[BlockHash] = {
    val headerOfIntraDeps =
      deps.unorderedIntraDeps(mainGroup).view.map(intraDep => getBlockHeaderUnsafe(intraDep))
    brokerConfig.cliqueGroupIndexes.map { groupIndex =>
      headerOfIntraDeps
        .map { header =>
          if (header.isGenesis) {
            genesisHashes(groupIndex.value)(groupIndex.value) -> ALPH.GenesisHeight
          } else {
            val intraDep = header.getIntraDep(groupIndex)
            val height   = getHeightUnsafe(intraDep)
            intraDep -> height
          }
        }
        .minBy(_._2)
        ._1
    }
  }

  def getOutTips(header: BlockHeader): AVector[BlockHash]

  private[core] val diffAndTimeSpanCache = Cache.fifoSafe[BlockHash, (Difficulty, Duration)](
    consensusConfigs.blockCacheCapacityPerChain * brokerConfig.chainNum * 8
  )
  def getDiffAndTimeSpanUnsafe(
      hash: BlockHash
  )(implicit consensusConfig: ConsensusSetting): (Difficulty, Duration) = {
    diffAndTimeSpanCache.get(hash).getOrElse {
      if (hash == BlockHash.zero) {
        (
          consensusConfig.maxMiningTarget.getDifficulty(),
          consensusConfig.expectedWindowTimeSpan
        )
      } else {
        val diff   = getBlockHeaderUnsafe(hash).target.getDifficulty()
        val height = getHeightUnsafe(hash)
        if (ChainDifficultyAdjustment.enoughHeight(height)) {
          val (timestampLast, timestampNow) =
            Utils.unsafe(getHashChain(hash).calTimeSpan(hash, height))
          (diff, timestampNow.deltaUnsafe(timestampLast))
        } else {
          (diff, consensusConfig.expectedWindowTimeSpan)
        }
      }
    }
  }

  private[core] val diffAndTimeSpanForIntraDepCache =
    Cache.fifoSafe[BlockHash, (Difficulty, Duration, TimeStamp)](
      consensusConfigs.blockCacheCapacityPerChain * brokerConfig.chainNum * 8
    )
  def getDiffAndTimeSpanForIntraDepUnsafe(
      intraDep: BlockHash
  )(implicit consensusConfig: ConsensusSetting): (Difficulty, Duration, TimeStamp) = {
    diffAndTimeSpanForIntraDepCache.get(intraDep).getOrElse {
      if (intraDep == BlockHash.zero) {
        (
          consensusConfig.maxMiningTarget.getDifficulty().times(brokerConfig.groups),
          consensusConfig.expectedWindowTimeSpan.timesUnsafe(brokerConfig.groups.toLong),
          ALPH.GenesisTimestamp
        )
      } else {
        assume(ChainIndex.from(intraDep).isIntraGroup)

        var diffSum     = Difficulty.zero.value
        var timeSpanSum = Duration.zero
        val outDeps     = getOutTips(getBlockHeaderUnsafe(intraDep))
        outDeps.foreach { dep =>
          val (diff, timeSpan) = getDiffAndTimeSpanUnsafe(dep)
          diffSum = diffSum.add(diff.value)
          timeSpanSum = timeSpanSum + timeSpan
        }
        @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
        val oldestTs = outDeps.view.map(dep => getHashChain(dep).getTimestampUnsafe(dep)).min
        (Difficulty.unsafe(diffSum), timeSpanSum, oldestTs)
      }
    }
  }

  def cacheDiffAndTimeSpan(header: BlockHeader): Unit = {
    val hardFork        = networkConfig.getHardFork(header.timestamp)
    val consensusConfig = consensusConfigs.getConsensusConfig(hardFork)
    diffAndTimeSpanCache.put(header.hash, getDiffAndTimeSpanUnsafe(header.hash)(consensusConfig))
    if (header.chainIndex.isIntraGroup) {
      diffAndTimeSpanForIntraDepCache.put(
        header.hash,
        getDiffAndTimeSpanForIntraDepUnsafe(header.hash)(consensusConfig)
      )
    }
  }

  def getDiffAndTimeSpanUnsafe(
      intraGroupDeps: AVector[BlockHash]
  )(implicit consensusConfig: ConsensusSetting): (Difficulty, Duration, TimeStamp) = {
    var diffSum     = BigInteger.valueOf(0)
    var timeSpanSum = Duration.zero
    var oldestTs    = TimeStamp.Max
    intraGroupDeps.foreach { intraDep =>
      val (diff, timeSpan, intraOldestTs) = getDiffAndTimeSpanForIntraDepUnsafe(intraDep)
      diffSum = diffSum.add(diff.value)
      timeSpanSum = timeSpanSum + timeSpan
      if (oldestTs > intraOldestTs) {
        oldestTs = intraOldestTs
      }
    }
    (Difficulty.unsafe(diffSum), timeSpanSum, oldestTs)
  }
}
