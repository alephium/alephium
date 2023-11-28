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

import org.alephium.protocol.config.NetworkConfig
import org.alephium.protocol.model.Block
import org.alephium.util.AVector

final case class BlockEntry(
    header: BlockHeaderEntry,
    transactions: AVector[Transaction],
    uncles: AVector[BlockHeaderEntry]
) {
  def toProtocol()(implicit networkConfig: NetworkConfig): Either[String, Block] = {
    for {
      protocolHeader <- header.toProtocol()
      _              <- Either.cond(protocolHeader.hash == header.hash, (), "Invalid hash")
      transactions   <- transactions.mapE(_.toProtocol())
      uncles         <- uncles.mapE(_.toProtocol())
    } yield Block(protocolHeader, uncles, transactions)
  }
}

object BlockEntry {
  def from(block: Block, height: Int, uncles: AVector[BlockHeaderEntry]): BlockEntry =
    BlockEntry(
      header = BlockHeaderEntry.from(block.header, height),
      transactions = block.transactions.map(Transaction.fromProtocol),
      uncles = uncles
    )
}
