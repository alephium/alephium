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

import org.alephium.protocol.{ALF, Hash}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.mining.PoW
import org.alephium.serde.{u256Serde => _, _}
import org.alephium.util.{AVector, TimeStamp, U256}

/** The header of a block with index.from == _group_
  *
  * @param blockDeps If the block is genesis, then it's empty.
  *                  Otherwise, 2 * G - 1 deps in total:
  *                  the first G - 1 hashes are from groups different from _group_
  *                  the rest G hashes are from all the chain related to _group_
  */
final case class BlockHeader(
    blockDeps: AVector[Hash],
    txsHash: Hash,
    timestamp: TimeStamp,
    target: Target,
    nonce: U256
) extends FlowData {
  lazy val hash: Hash = PoW.hash(this)

  def isGenesis: Boolean = timestamp == ALF.GenesisTimestamp

  def parentHash(implicit config: GroupConfig): Hash = {
    uncleHash(chainIndex.to)
  }

  def uncleHash(toIndex: GroupIndex)(implicit config: GroupConfig): Hash = {
    assume(!isGenesis)
    blockDeps.takeRight(config.groups)(toIndex.value)
  }

  def inDeps(implicit config: GroupConfig): AVector[Hash] = {
    assume(!isGenesis)
    blockDeps.dropRight(config.groups)
  }

  def outDeps(implicit config: GroupConfig): AVector[Hash] = {
    assume(!isGenesis)
    blockDeps.takeRight(config.groups)
  }

  def intraDep(implicit config: GroupConfig): Hash = {
    assume(!isGenesis)
    blockDeps.takeRight(config.groups)(chainIndex.from.value)
  }

  def outTips(implicit config: GroupConfig): AVector[Hash] = {
    assume(!isGenesis)
    blockDeps.takeRight(config.groups).replace(chainIndex.to.value, hash)
  }
}

object BlockHeader {
  // use fixed width bytes for U256 serialization
  private implicit val nonceSerde: Serde[U256] = Serde.bytesSerde(32).xmap(U256.unsafe, _.toBytes)

  implicit val serde: Serde[BlockHeader] =
    Serde.forProduct5(apply, bh => (bh.blockDeps, bh.txsHash, bh.timestamp, bh.target, bh.nonce))

  def genesis(txsHash: Hash, target: Target, nonce: U256): BlockHeader = {
    BlockHeader(AVector.empty, txsHash, ALF.GenesisTimestamp, target, nonce)
  }
}
