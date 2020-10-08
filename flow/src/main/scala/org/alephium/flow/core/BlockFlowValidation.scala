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
import org.alephium.protocol.Hash
import org.alephium.protocol.model.{Block, BlockHeader}
import org.alephium.util.AVector

trait BlockFlowValidation extends ConflictedBlocks with FlowTipsUtil { self: BlockFlow =>
  def getBlockUnsafe(hash: Hash): Block

  def checkFlowBlock(block: Block): IOResult[Boolean] =
    IOUtils.tryExecute(checkFlowUnsafe(block))

  def checkFlowHeader(header: BlockHeader): IOResult[Boolean] =
    IOUtils.tryExecute(checkFlowUnsafe(header))

  def checkFlowUnsafe(block: Block): Boolean = {
    assume(!block.isGenesis)

    checkFlowDepsUnsafe(block.header, checkTxConflicts = true) && checkFlowTxsUnsafe(block)
  }

  def checkFlowUnsafe(header: BlockHeader): Boolean = {
    assume(!header.isGenesis)

    checkFlowDepsUnsafe(header, checkTxConflicts = false)
  }

  def checkFlowDepsUnsafe(header: BlockHeader, checkTxConflicts: Boolean): Boolean = {
    val targetGroup = header.chainIndex.from
    assume(!(checkTxConflicts ^ brokerConfig.contains(targetGroup)))

    val blockDeps   = header.blockDeps
    val initialTips = getFlowTipsUnsafe(blockDeps.head, targetGroup)

    @tailrec
    def iter(currentTips: FlowTips, tips: AVector[Hash]): Option[FlowTips] = {
      if (tips.isEmpty) Some(currentTips)
      else {
        tryMergeUnsafe(currentTips, tips.head, targetGroup, checkTxConflicts) match {
          case Some(merged) => iter(merged, tips.tail)
          case None         => None
        }
      }
    }

    iter(initialTips, blockDeps.tail).nonEmpty
  }

  def checkFlowTxsUnsafe(block: Block): Boolean = {
    val newOutTips = block.header.outDeps
    val intraDep   = block.header.intraDep
    val oldOutTips = getOutTips(getBlockHeaderUnsafe(intraDep), inclusive = true)
    val diff       = getTipsDiffUnsafe(newOutTips, oldOutTips)
    cacheForConflicts(block)
    !isConflicted(block.hash, diff, getBlockUnsafe)
  }
}
