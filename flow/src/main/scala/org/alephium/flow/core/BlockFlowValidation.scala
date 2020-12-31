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

import org.alephium.io.{IOResult, IOUtils}
import org.alephium.protocol.BlockHash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Block, BlockDeps, BlockHeader, GroupIndex}
import org.alephium.util.AVector

trait BlockFlowValidation extends ConflictedBlocks with FlowTipsUtil { self: BlockFlow =>
  def getBlockUnsafe(hash: BlockHash): Block

  def checkFlowTxs(block: Block): IOResult[Boolean] =
    IOUtils.tryExecute(checkFlowTxsUnsafe(block))

  def checkFlowDeps(header: BlockHeader): IOResult[Boolean] =
    IOUtils.tryExecute(checkFlowDepsUnsafe(header))

  def checkFlowDepsUnsafe(blockDeps: BlockDeps, targetGroup: GroupIndex): Boolean = {
    val bestDep     = blockDeps.deps.max(blockHashOrdering)
    val initialTips = getFlowTipsUnsafe(bestDep, targetGroup)

    val sortedDeps = BlockFlowValidation.sortDeps(blockDeps, bestDep, targetGroup)

    @tailrec
    def iter(currentTips: FlowTips, tips: AVector[BlockHash]): Option[FlowTips] = {
      if (tips.isEmpty) {
        Some(currentTips)
      } else {
        tryMergeUnsafe(currentTips, tips.head, targetGroup, checkTxConflicts = false) match {
          case Some(merged) => iter(merged, tips.tail)
          case None         => None
        }
      }
    }

    val result = iter(initialTips, sortedDeps.filter(_ != bestDep)) match {
      case Some(flowTips) => flowTips.sameAs(blockDeps)
      case None           => false
    }
    if (!result) dumpInvalidFlow(blockDeps, targetGroup)
    result
  }

  def dumpInvalidFlow(blockDeps: BlockDeps, targetGroup: GroupIndex): Unit = {
    var s =
      s"=========== Invalid Flow for group ${targetGroup.value}: ${blockDeps.deps.map(_.shortHex).mkString("-")}\n"

    val bestDep     = blockDeps.deps.max(blockHashOrdering)
    val initialTips = getFlowTipsUnsafe(bestDep, targetGroup)
    val sortedDeps  = BlockFlowValidation.sortDeps(blockDeps, bestDep, targetGroup)

    s += s"bestDep: ${bestDep.shortHex}\n"
    s += s"sortedDeps: ${sortedDeps.map(_.shortHex).mkString("-")}\n"

    @tailrec
    def iter(currentTips: FlowTips, tips: AVector[BlockHash]): Unit = {
      s += (s"currentTips: ${currentTips.toBlockDeps.deps.map(_.shortHex).mkString("-")}\n")
      if (tips.nonEmpty) {
        s += (s"next tip: ${tips.head.shortHex}\n")

        tryMergeUnsafe(currentTips, tips.head, targetGroup, checkTxConflicts = false) match {
          case Some(merged) => iter(merged, tips.tail)
          case None         => s += "Cannot merge\n"
        }
      }
    }

    iter(initialTips, sortedDeps.filter(_ != bestDep))
    s += (s"===========\n")

    println(s)
  }

  def checkFlowDepsUnsafe(header: BlockHeader): Boolean = {
    val targetGroup = header.chainIndex.from
    checkFlowDepsUnsafe(header.blockDeps, targetGroup)
  }

  def checkFlowTxsUnsafe(block: Block): Boolean = {
    val newOutTips = block.header.outDeps
    val intraDep   = block.header.intraDep
    val oldOutTips = getOutTips(getBlockHeaderUnsafe(intraDep), inclusive = false)
    val diff       = getTipsDiffUnsafe(newOutTips, oldOutTips)
    !isConflicted(block.hash +: diff,
                  hash => if (hash == block.hash) block else getBlockUnsafe(hash))
  }
}

object BlockFlowValidation {
  def sortDeps(blockDeps: BlockDeps, bestDep: BlockHash, targetGroup: GroupIndex)(
      implicit groupConfig: GroupConfig): AVector[BlockHash] = {
    val groupOrders = BlockFlow.randomGroupOrders(bestDep)
    val outDeps     = blockDeps.outDeps
    val inDeps      = blockDeps.inDeps

    val sortedDeps = Array.ofDim[BlockHash](groupConfig.depsNum)
    (0 until groupConfig.groups).foreach { k =>
      sortedDeps(k) = outDeps(groupOrders(k))
    }
    var count = 0
    groupOrders.foreach { order =>
      if (order < targetGroup.value) {
        sortedDeps(groupConfig.groups + count) = inDeps(order)
        count += 1
      } else if (order > targetGroup.value) {
        sortedDeps(groupConfig.groups + count) = inDeps(order - 1)
        count += 1
      }
    }

    AVector.unsafe(sortedDeps)
  }
}
