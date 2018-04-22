package org.alephium.protocol.model

import org.alephium.crypto.Keccak256
import org.alephium.serde.Serde
import org.alephium.util.{UInt, WithKeccak256}

case class Block(blockHeader: BlockHeader, transactions: Seq[Transaction])
    extends WithKeccak256[Block]

object Block {
  implicit val serde: Serde[Block] = Serde.forProduct2(apply, b => (b.blockHeader, b.transactions))

  def from(blockDeps: Seq[Keccak256], transactions: Seq[Transaction], nonce: UInt): Block = {
    // TODO: validate all the block dependencies; the first block dep should be previous block in the same chain
    val txsHash     = Keccak256.hash(transactions)
    val timestamp   = System.currentTimeMillis()
    val blockHeader = BlockHeader(blockDeps, txsHash, timestamp, nonce)
    Block(blockHeader, transactions)
  }
}
