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

import scala.annotation.tailrec

import org.alephium.flow.Utils
import org.alephium.io.IOResult
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model._
import org.alephium.util.{AVector, EitherF}

// scalastyle:off number.of.methods
trait FlowTipsUtil {
  implicit def brokerConfig: BrokerConfig

  def groups: Int
  def initialGenesisHashes: AVector[BlockHash]
  def genesisHashes: AVector[AVector[BlockHash]]
  def getBestDeps(groupIndex: GroupIndex): BlockDeps

  def getHeightUnsafe(hash: BlockHash): Int
  def getBlockUnsafe(hash: BlockHash): Block
  def getBlockHeader(hash: BlockHash): IOResult[BlockHeader]
  def getBlockHeaderUnsafe(hash: BlockHash): BlockHeader
  def getHashChain(hash: BlockHash): BlockHashChain
  def getHashChain(chainIndex: ChainIndex): BlockHashChain

  def isConflicted(hashes: AVector[BlockHash], getBlock: BlockHash => Block): Boolean

  def getInTip(dep: BlockHash, currentGroup: GroupIndex): IOResult[BlockHash] = {
    getBlockHeader(dep).map { header =>
      val from = header.chainIndex.from
      if (header.isGenesis) {
        genesisHashes(from.value)(currentGroup.value)
      } else {
        if (currentGroup == ChainIndex.from(dep).to) dep else header.uncleHash(currentGroup)
      }
    }
  }

  def getOutTip(header: BlockHeader, outGroup: GroupIndex): BlockHash = {
    if (header.isGenesis) {
      genesisHashes(header.chainIndex.from.value)(outGroup.value)
    } else {
      header.getOutTip(outGroup)
    }
  }

  def getOutTips(header: BlockHeader): AVector[BlockHash] = {
    if (header.isGenesis) {
      val index = header.chainIndex
      genesisHashes(index.from.value)
    } else {
      header.outTips
    }
  }

  def getGroupTip(header: BlockHeader, targetGroup: GroupIndex): BlockHash = {
    if (header.isGenesis) {
      genesisHashes(targetGroup.value)(targetGroup.value)
    } else {
      header.getGroupTip(targetGroup)
    }
  }

  // if inclusive is true, the current header would be included
  def getInOutTips(
      header: BlockHeader,
      currentGroup: GroupIndex
  ): IOResult[AVector[BlockHash]] = {
    assume(currentGroup == header.chainIndex.from)
    if (header.isGenesis) {
      val inTips = AVector.tabulate(groups - 1) { i =>
        if (i < currentGroup.value) {
          genesisHashes(i)(currentGroup.value)
        } else {
          genesisHashes(i + 1)(currentGroup.value)
        }
      }
      val outTips = genesisHashes(currentGroup.value)
      Right(inTips ++ outTips)
    } else {
      val outTips = getOutTips(header)
      header.inDeps.mapE(getInTip(_, currentGroup)).map(_ ++ outTips)
    }
  }

  def getInOutTips(
      hash: BlockHash,
      currentGroup: GroupIndex
  ): IOResult[AVector[BlockHash]] = {
    getBlockHeader(hash).flatMap(getInOutTips(_, currentGroup))
  }

  def getTipsDiff(newTip: BlockHash, oldTip: BlockHash): IOResult[AVector[BlockHash]] = {
    getHashChain(oldTip).getBlockHashesBetween(newTip, oldTip)
  }

  def getTipsDiffUnsafe(
      newTips: AVector[BlockHash],
      oldTips: AVector[BlockHash]
  ): AVector[BlockHash] = {
    Utils.unsafe(getTipsDiff(newTips, oldTips))
  }

  protected def getTipsDiff(
      newTips: AVector[BlockHash],
      oldTips: AVector[BlockHash]
  ): IOResult[AVector[BlockHash]] = {
    assume(newTips.length == oldTips.length)
    EitherF.foldTry(newTips.indices, AVector.empty[BlockHash]) { (acc, i) =>
      getTipsDiff(newTips(i), oldTips(i)).map(acc ++ _)
    }
  }

  private[core] def getFlowTipsUnsafe(tip: BlockHash, targetGroup: GroupIndex): FlowTips = {
    val header = getBlockHeaderUnsafe(tip)
    getFlowTipsUnsafe(header, targetGroup)
  }

  private[core] def getFlowTipsUnsafe(header: BlockHeader, targetGroup: GroupIndex): FlowTips = {
    assume(header.chainIndex.isIntraGroup || header.chainIndex.from == targetGroup)

    val FlowTips.Light(inTips, targetTip) = getLightTipsUnsafe(header, targetGroup)
    val targetTips                        = getOutTipsUnsafe(targetTip)

    FlowTips(targetGroup, inTips, targetTips)
  }

  private[core] def getFlowTipsDiffUnsafe(
      newTips: FlowTips,
      oldTips: FlowTips
  ): AVector[BlockHash] = {
    val outDiffs = getTipsDiffUnsafe(newTips.outTips, oldTips.outTips)
    val groupDiffs = newTips.inTips.indices.foldLeft(AVector.empty[BlockHash]) { case (acc, g) =>
      acc ++ getGroupTipsDiffUnsafe(newTips.inTips(g), oldTips.inTips(g))
    }
    groupDiffs ++ outDiffs
  }

  private[core] def getGroupTipsDiffUnsafe(
      newTip: BlockHash,
      oldTip: BlockHash
  ): AVector[BlockHash] = {
    val newOutTips = getOutTips(getBlockHeaderUnsafe(newTip))
    val oldOutTips = getOutTips(getBlockHeaderUnsafe(oldTip))
    getTipsDiffUnsafe(newOutTips, oldOutTips)
  }

  private[core] def getLightTipsUnsafe(tip: BlockHash, targetGroup: GroupIndex): FlowTips.Light = {
    val header = getBlockHeaderUnsafe(tip)
    getLightTipsUnsafe(header, targetGroup)
  }

  private[core] def getLightTipsUnsafe(
      header: BlockHeader,
      targetGroup: GroupIndex
  ): FlowTips.Light = {
    assume(header.chainIndex.isIntraGroup || header.chainIndex.from == targetGroup)

    if (header.isGenesis) {
      val inTips = AVector.tabulate(groups - 1) { i =>
        val g = if (i < targetGroup.value) i else i + 1
        initialGenesisHashes(g)
      }
      val targetTip = initialGenesisHashes(targetGroup.value)
      FlowTips.Light(inTips, targetTip)
    } else {
      val inTips = AVector.tabulate(groups - 1) { i =>
        val g = if (i < targetGroup.value) i else i + 1
        header.getGroupTip(GroupIndex.unsafe(g))
      }
      val targetTip = header.getGroupTip(targetGroup)
      FlowTips.Light(inTips, targetTip)
    }
  }

  private[core] def getOutTipsUnsafe(
      tip: BlockHash,
      targetGroup: GroupIndex
  ): AVector[BlockHash] = {
    val header = getBlockHeaderUnsafe(tip)
    getOutTipsUnsafe(header, targetGroup)
  }

  private[core] def getOutTipsUnsafe(tip: BlockHash): AVector[BlockHash] = {
    val header = getBlockHeaderUnsafe(tip)
    getOutTips(header)
  }

  private[core] def getOutTipsUnsafe(
      header: BlockHeader,
      targetGroup: GroupIndex
  ): AVector[BlockHash] = {
    val index = header.chainIndex
    if (index.from == targetGroup) {
      getOutTips(header)
    } else {
      if (header.isGenesis) {
        genesisHashes(targetGroup.value)
      } else {
        getOutTipsUnsafe(header.getGroupTip(targetGroup))
      }
    }
  }

  def getIncomingBlockDeps(
      targetGroupIndex: GroupIndex,
      deps: BlockDeps
  ): IOResult[AVector[BlockHash]] = {
    deps.inDeps.foldE(AVector.empty[BlockHash]) { case (acc, inDep) =>
      val chainIndex = ChainIndex.from(inDep)
      assume(ChainIndex.from(inDep).isIntraGroup)
      if (brokerConfig.contains(chainIndex.from)) {
        val bestInDep = getBestDeps(chainIndex.from).getOutDep(targetGroupIndex)
        getInTip(inDep, targetGroupIndex).flatMap { bestTargetDep =>
          getTipsDiff(bestInDep, bestTargetDep).map(acc ++ _)
        }
      } else {
        Right(acc)
      }
    }
  }

  // TODO: large room for optimizations
  private[core] def tryMergeUnsafe(
      flowTips: FlowTips,
      tip: BlockHash,
      targetGroup: GroupIndex
  ): Option[FlowTips] = {
    if (flowTips.outTips.contains(tip) || flowTips.inTips.contains(tip)) {
      Some(flowTips)
    } else {
      tryMergeUnsafe(targetGroup, flowTips, getLightTipsUnsafe(tip, targetGroup))
    }
  }

  private[core] def tryMergeUnsafe(
      targetGroup: GroupIndex,
      flowTips: FlowTips,
      newTips: FlowTips.Light
  ): Option[FlowTips] = {
    for {
      newInTips  <- mergeInTips(flowTips.inTips, newTips.inTips)
      newOutTips <- mergeOutTips(targetGroup, flowTips.outTips, newTips.outTip)
    } yield FlowTips(flowTips.targetGroup, newInTips, newOutTips)
  }

  private[core] def merge(tip1: BlockHash, tip2: BlockHash): Option[BlockHash] = {
    if (isExtendingUnsafe(tip1, tip2)) {
      Some(tip1)
    } else if (isExtendingUnsafe(tip2, tip1)) {
      Some(tip2)
    } else {
      None
    }
  }

  private[core] def mergeTips(
      tips1: AVector[BlockHash],
      tips2: AVector[BlockHash]
  ): Option[AVector[BlockHash]] = {
    assume(tips1.length == tips2.length)

    @tailrec
    def iter(acc: AVector[BlockHash], g: Int): Option[AVector[BlockHash]] = {
      if (g == tips1.length) {
        Some(acc)
      } else {
        merge(tips1(g), tips2(g)) match {
          case Some(merged) => iter(acc :+ merged, g + 1)
          case None         => None
        }
      }
    }

    iter(AVector.ofCapacity(tips1.length), 0)
  }

  private[core] def mergeInTips(
      inTips1: AVector[BlockHash],
      inTips2: AVector[BlockHash]
  ): Option[AVector[BlockHash]] = {
    mergeTips(inTips1, inTips2)
  }

  private[core] def mergeOutTips(
      targetGroup: GroupIndex,
      outTips: AVector[BlockHash],
      newTip: BlockHash
  ): Option[AVector[BlockHash]] = {
    assume(outTips.length == groups)
    val newTipIndex = ChainIndex.from(newTip)

    if (outTips(newTipIndex.to.value) == newTip) {
      Some(outTips)
    } else {
      val newOutTips = getOutTipsUnsafe(newTip)
      mergeTips(outTips, newOutTips).flatMap { mergedDeps =>
        val intraDep0 = outTips(targetGroup.value)
        val intraDep1 = getOutTip(getBlockHeaderUnsafe(newTip), targetGroup)
        val commonIntraDep =
          if (getHeightUnsafe(intraDep0) <= getHeightUnsafe(intraDep1)) intraDep0 else intraDep1
        val commonOutTips = getOutTips(getBlockHeaderUnsafe(commonIntraDep))
        val diffs         = getTipsDiffUnsafe(mergedDeps, commonOutTips)
        Option.when(diffs.isEmpty || (!isConflicted(diffs, getBlockUnsafe)))(mergedDeps)
      }
    }
  }

  private[core] def isExtendingUnsafe(current: BlockHash, previous: BlockHash): Boolean = {
    val index1 = ChainIndex.from(current)
    val index2 = ChainIndex.from(previous)
    assume(index1.from == index2.from)

    val chain = getHashChain(index2)
    if (index1.to == index2.to) {
      Utils.unsafe(chain.isBefore(previous, current))
    } else {
      val groupDeps = getOutTipsUnsafe(current, index1.from)
      Utils.unsafe(chain.isBefore(previous, groupDeps(index2.to.value)))
    }
  }

  private[flow] def isExtendingUnsafe(current: BlockDeps, previous: BlockDeps): Boolean = {
    current.deps.forallWithIndex { case (currentHash, index) =>
      val previousHash = previous.deps(index)
      isExtendingUnsafe(currentHash, previousHash)
    }
  }
}
// scalastyle:on
