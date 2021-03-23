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

import org.alephium.io.{IOResult, IOUtils}
import org.alephium.protocol.BlockHash
import org.alephium.protocol.model.{Block, BlockDeps, BlockHeader, GroupIndex}

trait BlockFlowValidation extends ConflictedBlocks with FlowTipsUtil { self: BlockFlow =>
  def getBlockUnsafe(hash: BlockHash): Block

  def checkFlowTxs(block: Block): IOResult[Boolean] =
    IOUtils.tryExecute(checkFlowTxsUnsafe(block))

  def checkFlowDeps(header: BlockHeader): IOResult[Boolean] =
    IOUtils.tryExecute(checkFlowDepsUnsafe(header))

  def checkFlowDepsUnsafe(blockDeps: BlockDeps, targetGroup: GroupIndex): Boolean = {
    blockDeps.deps.forall { dep =>
      val flowTips = getFlowTipsUnsafe(dep, targetGroup)

      val ok1 = flowTips.inTips.forallWithIndex { case (depInTip, k) =>
        val blockInTip = blockDeps.inDeps(k)
        isExtendingUnsafe(blockInTip, depInTip)
      }
      val ok2 = flowTips.outTips.forallWithIndex { case (depOutTip, k) =>
        val blockOutTip = blockDeps.outDeps(k)
        isExtendingUnsafe(blockOutTip, depOutTip)
      }
      ok1 && ok2
    }
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
    !isConflicted(
      block.hash +: diff,
      hash => if (hash == block.hash) block else getBlockUnsafe(hash)
    )
  }
}
