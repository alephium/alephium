package org.alephium.protocol.model

import org.alephium.crypto.{Keccak256, Keccak256Hash}
import org.alephium.protocol.config.GroupConfig
import org.alephium.serde.Serde
import org.alephium.util.{AVector, TimeStamp}

case class Block(header: BlockHeader, transactions: AVector[Transaction])
    extends Keccak256Hash[Block]
    with FlowData {
  override def hash: Keccak256 = header.hash

  override def timestamp: TimeStamp = header.timestamp

  override def target: BigInt = header.target

  def chainIndex(implicit config: GroupConfig): ChainIndex = {
    header.chainIndex
  }

  def isGenesis: Boolean = header.isGenesis

  def parentHash(implicit config: GroupConfig): Keccak256 = {
    header.parentHash
  }

  def uncleHash(toIndex: GroupIndex)(implicit config: GroupConfig): Keccak256 = {
    header.uncleHash(toIndex)
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
    val txsHash = Keccak256.hash(transactions)
    val blockHeader =
      BlockHeader(AVector.empty, txsHash, TimeStamp.unsafe(0), target, nonce)
    Block(blockHeader, transactions)
  }
}
