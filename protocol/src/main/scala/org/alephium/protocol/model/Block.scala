package org.alephium.protocol.model

import org.alephium.crypto.{Keccak256, Keccak256Hash}
import org.alephium.protocol.config.GroupConfig
import org.alephium.serde.Serde
import org.alephium.util.{AVector, TimeStamp}

case class Block(header: BlockHeader, transactions: AVector[Transaction])
    extends Keccak256Hash[Block] {
  override def hash: Keccak256 = header.hash

  def chainIndex(implicit config: GroupConfig): ChainIndex = {
    header.chainIndex
  }

  def parentHash(implicit config: GroupConfig): Keccak256 = {
    header.parentHash
  }

  def uncleHash(toIndex: GroupIndex)(implicit config: GroupConfig): Keccak256 = {
    header.uncleHash(toIndex)
  }

  def validateIndex(target: ChainIndex)(implicit config: GroupConfig): Boolean = {
    header.validateIndex(target)
  }
}

object Block {
  implicit val serde: Serde[Block] = Serde.forProduct2(apply, b => (b.header, b.transactions))

  def from(blockDeps: AVector[Keccak256],
           transactions: AVector[Transaction],
           target: BigInt,
           nonce: BigInt): Block = {
    // TODO: validate all the block dependencies; the first block dep should be previous block in the same chain
    val txsHash     = Keccak256.hash(transactions)
    val timestamp   = TimeStamp.now()
    val blockHeader = BlockHeader(blockDeps, txsHash, timestamp, target, nonce)
    Block(blockHeader, transactions)
  }

  def genesis(transactions: AVector[Transaction], target: BigInt, nonce: BigInt): Block = {
    val txsHash     = Keccak256.hash(transactions)
    val blockHeader = BlockHeader(AVector.empty, txsHash, TimeStamp.fromMillis(0), target, nonce)
    Block(blockHeader, transactions)
  }
}
