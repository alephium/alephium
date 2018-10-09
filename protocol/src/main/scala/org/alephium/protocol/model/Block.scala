package org.alephium.protocol.model

import org.alephium.crypto.{Keccak256, WithKeccak256}
import org.alephium.serde.Serde

case class Block(blockHeader: BlockHeader, transactions: Seq[Transaction])
    extends WithKeccak256[Block] {
  def miningHash: Keccak256 = Block.toMiningHash(this.hash)
}

object Block {
  implicit val serde: Serde[Block] = Serde.forProduct2(apply, b => (b.blockHeader, b.transactions))

  def from(blockDeps: Seq[Keccak256],
           transactions: Seq[Transaction],
           target: BigInt,
           nonce: BigInt): Block = {
    // TODO: validate all the block dependencies; the first block dep should be previous block in the same chain
    val txsHash     = Keccak256.hash(transactions)
    val timestamp   = System.currentTimeMillis()
    val blockHeader = BlockHeader(blockDeps, txsHash, timestamp, target, nonce)
    Block(blockHeader, transactions)
  }

  def from(blockDeps: Seq[Keccak256], timestamp: Long, target: BigInt, nonce: BigInt): Block = {
    // TODO: validate all the block dependencies; the first block dep should be previous block in the same chain
    val transactions = Seq.empty[Transaction]
    val txsHash      = Keccak256.hash(transactions)
    val blockHeader  = BlockHeader(blockDeps, txsHash, timestamp, target, nonce)
    Block(blockHeader, transactions)
  }

  def genesis(transactions: Seq[Transaction], target: BigInt, nonce: BigInt): Block = {
    val txsHash     = Keccak256.hash(transactions)
    val blockHeader = BlockHeader(Seq.empty, txsHash, 0, target, nonce)
    Block(blockHeader, transactions)
  }

  def toMiningHash(hash: Keccak256): Keccak256 = Keccak256.hash(hash)
}
