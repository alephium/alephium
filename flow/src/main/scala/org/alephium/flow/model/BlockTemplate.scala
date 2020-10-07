package org.alephium.flow.model

import org.alephium.protocol.Hash
import org.alephium.protocol.model.{Block, BlockHeader, Target, Transaction}
import org.alephium.util.{AVector, TimeStamp}

final case class BlockTemplate(deps: AVector[Hash],
                               target: Target,
                               txHash: Hash,
                               transactions: AVector[Transaction]) {

  def buildHeader(nonce: BigInt): BlockHeader =
    BlockHeader(deps, txHash, TimeStamp.now(), target, nonce)

  def buildBlock(nonce: BigInt): Block = {
    val header = buildHeader(nonce)
    Block(header, transactions)
  }
}

object BlockTemplate {

  def apply(deps: AVector[Hash],
            target: Target,
            transactions: AVector[Transaction]): BlockTemplate = {
    val txHash = Hash.hash(transactions)
    BlockTemplate(deps, target, txHash, transactions)
  }
}
