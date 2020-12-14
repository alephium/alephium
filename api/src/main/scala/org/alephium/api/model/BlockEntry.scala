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

package org.alephium.api.model

import org.alephium.protocol.model.{Block, BlockHeader, NetworkType}
import org.alephium.util.{AVector, TimeStamp}

final case class BlockEntry(
    hash: String,
    timestamp: TimeStamp,
    chainFrom: Int,
    chainTo: Int,
    height: Int,
    deps: AVector[String],
    transactions: Option[AVector[Tx]]
)
object BlockEntry {
  def from(header: BlockHeader, height: Int): BlockEntry = {
    BlockEntry(
      hash         = header.hash.toHexString,
      timestamp    = header.timestamp,
      chainFrom    = header.chainIndex.from.value,
      chainTo      = header.chainIndex.to.value,
      height       = height,
      deps         = header.blockDeps.deps.map(_.toHexString),
      transactions = None
    )
  }

  def from(block: Block, height: Int, networkType: NetworkType): BlockEntry =
    from(block.header, height)
      .copy(transactions = Some(block.transactions.map(Tx.from(_, networkType))))

}
