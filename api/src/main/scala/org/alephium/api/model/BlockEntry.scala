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

import akka.util.ByteString

import org.alephium.protocol.{BlockHash, Hash}
import org.alephium.protocol.config.NetworkConfig
import org.alephium.protocol.model.{Block, BlockDeps, BlockHeader, Nonce, Target}
import org.alephium.util.{AVector, TimeStamp}

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class BlockEntry(
    hash: BlockHash,
    timestamp: TimeStamp,
    chainFrom: Int,
    chainTo: Int,
    height: Int,
    deps: AVector[BlockHash],
    transactions: AVector[Transaction],
    nonce: ByteString,
    version: Byte,
    depStateHash: Hash,
    txsHash: Hash,
    target: ByteString
) {
  def toProtocol()(implicit networkConfig: NetworkConfig): Either[String, Block] = {
    for {
      header       <- toBlockHeader()
      _            <- Either.cond(hash == header.hash, (), "Invalid hash")
      transactions <- transactions.mapE(_.toProtocol())
    } yield {
      Block(
        header,
        transactions
      )
    }
  }

  def toBlockHeader(): Either[String, BlockHeader] =
    for {
      _nonce <- Nonce.from(nonce).toRight("Invalid nonce")
    } yield {
      BlockHeader(
        _nonce,
        version,
        BlockDeps.unsafe(deps),
        depStateHash,
        txsHash,
        timestamp,
        Target.unsafe(target)
      )
    }
}

object BlockEntry {
  def from(block: Block, height: Int): BlockEntry =
    BlockEntry(
      hash = block.header.hash,
      timestamp = block.header.timestamp,
      chainFrom = block.header.chainIndex.from.value,
      chainTo = block.header.chainIndex.to.value,
      height = height,
      deps = block.header.blockDeps.deps,
      transactions = block.transactions.map(Transaction.fromProtocol(_)),
      nonce = block.header.nonce.value,
      version = block.header.version,
      depStateHash = block.header.depStateHash,
      txsHash = block.header.txsHash,
      target = block.header.target.bits
    )
}
