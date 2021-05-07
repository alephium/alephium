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

import org.alephium.protocol.{BlockHash, Hash}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.util.{AVector, TimeStamp, U256}

final case class BlockTemplate(
    deps: AVector[BlockHash],
    depStateHash: Hash,
    target: Target,
    blockTs: TimeStamp,
    txsHash: Hash,
    transactions: AVector[Transaction]
) {
  def buildHeader(nonce: U256)(implicit config: GroupConfig): BlockHeader =
    BlockHeader.unsafe(BlockDeps.build(deps), depStateHash, txsHash, blockTs, target, nonce)
}

object BlockTemplate {
  def apply(
      deps: AVector[BlockHash],
      depStateHash: Hash,
      target: Target,
      blockTs: TimeStamp,
      transactions: AVector[Transaction]
  ): BlockTemplate = {
    val txsHash = Block.calTxsHash(transactions)
    BlockTemplate(deps, depStateHash, target, blockTs, txsHash, transactions)
  }
}
