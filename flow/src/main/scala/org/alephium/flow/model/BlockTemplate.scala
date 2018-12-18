package org.alephium.flow.model

import org.alephium.crypto.Keccak256
import org.alephium.protocol.model.{Block, BlockHeader, Transaction}
import org.alephium.util.AVector

case class BlockTemplate(deps: AVector[Keccak256],
                         target: BigInt,
                         txHash: Keccak256,
                         transactions: AVector[Transaction]) {

  def buildHeader(nonce: BigInt): BlockHeader =
    BlockHeader(deps, txHash, System.currentTimeMillis(), target, nonce)

  def buildBlock(nonce: BigInt): Block = {
    val header = buildHeader(nonce)
    Block(header, transactions)
  }
}

object BlockTemplate {

  def apply(deps: AVector[Keccak256],
            target: BigInt,
            transactions: AVector[Transaction]): BlockTemplate = {
    val txHash = Keccak256.hash(transactions)
    BlockTemplate(deps, target, txHash, transactions)
  }
}
