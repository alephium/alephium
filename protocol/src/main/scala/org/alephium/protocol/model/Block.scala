package org.alephium.protocol.model

import org.alephium.crypto.{Keccak256, WithKeccak256}
import org.alephium.protocol.config.ConsensusConfig
import org.alephium.serde.Serde
import org.alephium.util.AVector

case class Block(blockHeader: BlockHeader, transactions: AVector[Transaction])
    extends WithKeccak256[Block] {
  def miningHash: Keccak256 = Block.toMiningHash(this.hash)

  def chainIndex(implicit config: ConsensusConfig): ChainIndex = {
    ChainIndex.fromHash(hash)
  }

  def parentHash(implicit config: ConsensusConfig): Keccak256 = {
    uncleHash(chainIndex.to)
  }

  // when toIndex == chainIndex.to, it returns hash of parent
  def uncleHash(toIndex: Int)(implicit config: ConsensusConfig): Keccak256 = {
    assert(toIndex == chainIndex.to)
    blockHeader.blockDeps.takeRight(config.groups)(toIndex)
  }
}

object Block {
  implicit val serde: Serde[Block] = Serde.forProduct2(apply, b => (b.blockHeader, b.transactions))

  def from(blockDeps: AVector[Keccak256],
           transactions: AVector[Transaction],
           target: BigInt,
           nonce: BigInt): Block = {
    // TODO: validate all the block dependencies; the first block dep should be previous block in the same chain
    val txsHash     = Keccak256.hash(transactions)
    val timestamp   = System.currentTimeMillis()
    val blockHeader = BlockHeader(blockDeps, txsHash, timestamp, target, nonce)
    Block(blockHeader, transactions)
  }

  def from(blockDeps: AVector[Keccak256], timestamp: Long, target: BigInt, nonce: BigInt): Block = {
    // TODO: validate all the block dependencies; the first block dep should be previous block in the same chain
    val transactions = AVector.empty[Transaction]
    val txsHash      = Keccak256.hash(transactions)
    val blockHeader  = BlockHeader(blockDeps, txsHash, timestamp, target, nonce)
    Block(blockHeader, transactions)
  }

  def genesis(transactions: AVector[Transaction], target: BigInt, nonce: BigInt): Block = {
    val txsHash     = Keccak256.hash(transactions)
    val blockHeader = BlockHeader(AVector.empty, txsHash, 0, target, nonce)
    Block(blockHeader, transactions)
  }

  def toMiningHash(hash: Keccak256): Keccak256 = Keccak256.hash(hash)
}
