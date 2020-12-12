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

package org.alephium.protocol.model

import org.alephium.protocol.BlockHash
import org.alephium.protocol.config.GroupConfig
import org.alephium.serde.Serde
import org.alephium.util.AVector

/*
 * There are 2 * groups - 1 dependent hashes for each block
 * The first G - 1 hashes are from groups different from this group
 * The rest G hashes are from all the chain related to this group
 */
final case class BlockDeps private (deps: AVector[BlockHash]) extends AnyVal {
  def length: Int = deps.length

  def getOutDep(to: GroupIndex)(implicit config: GroupConfig): BlockHash = {
    outDeps.apply(to.value)
  }

  def parentHash(chainIndex: ChainIndex)(implicit config: GroupConfig): BlockHash = {
    getOutDep(chainIndex.to)
  }

  def uncleHash(toIndex: GroupIndex)(implicit config: GroupConfig): BlockHash = {
    getOutDep(toIndex)
  }

  def outDeps(implicit config: GroupConfig): AVector[BlockHash] = {
    deps.takeRight(config.groups)
  }

  def inDeps(implicit config: GroupConfig): AVector[BlockHash] = {
    deps.dropRight(config.groups)
  }

  def intraDep(chainIndex: ChainIndex)(implicit config: GroupConfig): BlockHash = {
    getOutDep(chainIndex.from)
  }
}

object BlockDeps {
  implicit val serde: Serde[BlockDeps] = Serde.forProduct1(unsafe, t => t.deps)

  def unsafe(deps: AVector[BlockHash]): BlockDeps = {
    new BlockDeps(deps)
  }

  def build(deps: AVector[BlockHash])(implicit config: GroupConfig): BlockDeps = {
    require(deps.length == config.depsNum)
    new BlockDeps(deps)
  }
}
