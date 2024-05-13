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
import org.alephium.protocol.model._
import org.alephium.util.{AVector, TimeStamp}

final case class BlockFlowTemplate(
    index: ChainIndex,
    deps: AVector[BlockHash],
    depStateHash: Hash,
    target: Target,
    templateTs: TimeStamp,
    transactions: AVector[Transaction]
) {
  lazy val txsHash = Block.calTxsHash(transactions)

  def dummyHeader(): BlockHeader =
    BlockHeader.unsafe(
      BlockDeps.unsafe(deps),
      depStateHash,
      txsHash,
      templateTs,
      target,
      Nonce.zero
    )
}

object BlockFlowTemplate {
  def from(block: Block): BlockFlowTemplate = {
    BlockFlowTemplate(
      block.chainIndex,
      block.blockDeps.deps,
      block.header.depStateHash,
      block.header.target,
      block.timestamp,
      block.transactions
    )
  }
}
