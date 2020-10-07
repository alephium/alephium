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

package org.alephium.flow.model

import org.alephium.protocol.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.GroupIndex
import org.alephium.util.AVector

/*
 * There are 2 * groups - 1 dependent hashes for each block
 * The first groups - 1 hashes are for the incoming chains of a specific group
 * The last groups hashes are for the outcoming chains of a specific group
 */
final case class BlockDeps(deps: AVector[Hash]) {
  def getOutDep(to: GroupIndex)(implicit config: GroupConfig): Hash = {
    deps.takeRight(config.groups)(to.value)
  }

  def outDeps(implicit config: GroupConfig): AVector[Hash] = {
    deps.takeRight(config.groups)
  }

  def inDeps(implicit config: GroupConfig): AVector[Hash] = {
    deps.dropRight(config.groups)
  }
}
