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

import org.alephium.protocol.model.{BlockDeps, BlockHash, GroupIndex}
import org.alephium.util.AVector

final case class FlowTips(
    targetGroup: GroupIndex,
    inTips: AVector[BlockHash],
    outTips: AVector[BlockHash]
) {
  def toBlockDeps: BlockDeps = BlockDeps.unsafe(inTips ++ outTips)

  def sameAs(blockDeps: BlockDeps): Boolean = {
    inTips == blockDeps.inDeps && outTips == blockDeps.outDeps
  }
}

object FlowTips {
  final case class Light(inTips: AVector[BlockHash], outTip: BlockHash)

  def from(blockDeps: BlockDeps, targetGroup: GroupIndex): FlowTips = {
    FlowTips(targetGroup, blockDeps.inDeps, blockDeps.outDeps)
  }
}

/** This is used to represent the best blockflow with intra-group tips There are #groups dependent
  * hashes, each from a intra-group chain
  * @param intraGroupTips
  *   the tips of all intra-group chains
  * @param intraGroupTipOutDeps
  *   the cache for the outDeps of all tips
  */
final case class BlockFlowSkeleton(
    intraGroupTips: AVector[BlockHash],
    intraGroupTipOutDeps: AVector[AVector[BlockHash]]
) {
  def createBlockDeps(groupIndex: GroupIndex): BlockDeps = {
    val deps = intraGroupTips.remove(groupIndex.value) ++ intraGroupTipOutDeps(groupIndex.value)
    BlockDeps(deps)
  }
}

object BlockFlowSkeleton {
  final case class Builder(groups: Int) {
    val intraGroupTips: Array[BlockHash]                = Array.ofDim(groups)
    val intraGroupTipOutDeps: Array[AVector[BlockHash]] = Array.ofDim(groups)

    def setTip(group: GroupIndex, tip: BlockHash, tipOutTips: AVector[BlockHash]): Unit = {
      intraGroupTips(group.value) = tip
      intraGroupTipOutDeps(group.value) = tipOutTips
    }

    def getResult(): BlockFlowSkeleton = {
      BlockFlowSkeleton(AVector.unsafe(intraGroupTips), AVector.unsafe(intraGroupTipOutDeps))
    }
  }
}
