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
