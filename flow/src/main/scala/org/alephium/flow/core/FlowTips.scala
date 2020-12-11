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

import org.alephium.flow.model.BlockDeps
import org.alephium.protocol.BlockHash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{BlockHeader, GroupIndex}
import org.alephium.util.AVector

final case class FlowTips(targetGroup: GroupIndex,
                          inTips: AVector[BlockHash],
                          outTips: AVector[BlockHash]) {
  def toBlockDeps: BlockDeps = BlockDeps(inTips ++ outTips)

  def isDepsFor(header: BlockHeader)(implicit config: GroupConfig): Boolean = {
    inTips == header.inDeps && outTips == header.outDeps
  }
}

object FlowTips {
  final case class Light(inTips: AVector[BlockHash], outTip: BlockHash)
}
