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

import org.alephium.flow.model.BlockDeps
import org.alephium.io.{IOResult, IOUtils}
import org.alephium.protocol.BlockHash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Block, BlockHeader, GroupIndex}
import org.alephium.util.AVector

trait BlockFlowValidation extends ConflictedBlocks with FlowTipsUtil { self: BlockFlow =>
  def getBlockUnsafe(hash: BlockHash): Block

  def checkFlowDeps(block: Block): IOResult[Boolean] =
    IOUtils.tryExecute(checkFlowDepsUnsafe(block.header))

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

    iter(initialTips, sortedDeps.filter(_ != bestDep)) match {
      case Some(flowTips) => flowTips.sameAs(blockDeps)
      case None           => false
    }
  }

  def checkFlowDepsUnsafe(header: BlockHeader): Boolean = {
    val targetGroup = header.chainIndex.from
    checkFlowDepsUnsafe(BlockDeps(header.blockDeps), targetGroup)
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
    groupOrders.foreach { index =>
      sortedDeps(index) = outDeps(index)
      if (index < targetGroup.value) {
        sortedDeps(groupConfig.groups + index) = inDeps(index)
      } else if (index > targetGroup.value) {
        sortedDeps(groupConfig.groups + index - 1) = inDeps(index - 1)
      }
    }

    AVector.unsafe(sortedDeps)
  }
}
