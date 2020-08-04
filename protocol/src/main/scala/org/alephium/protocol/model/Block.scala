package org.alephium.protocol.model

import org.alephium.protocol.ALF.{Hash, HashSerde}
import org.alephium.protocol.config.GroupConfig
import org.alephium.serde.Serde
import org.alephium.util.{AVector, TimeStamp}

final case class Block(header: BlockHeader, transactions: AVector[Transaction])
    extends HashSerde[Block]
    with FlowData {
  override def hash: Hash = header.hash

  def coinbase: Transaction = transactions.last

  def nonCoinbase: AVector[Transaction] = transactions.init

  override def timestamp: TimeStamp = header.timestamp

  override def target: BigInt = header.target

  def chainIndex(implicit config: GroupConfig): ChainIndex = {
    header.chainIndex
  }

  def isGenesis: Boolean = header.isGenesis

  def parentHash(implicit config: GroupConfig): Hash = {
    header.parentHash
  }

  def uncleHash(toIndex: GroupIndex)(implicit config: GroupConfig): Hash = {
    header.uncleHash(toIndex)
  }
}

object Block {
  implicit val serde: Serde[Block] = Serde.forProduct2(apply, b => (b.header, b.transactions))

  def from(blockDeps: AVector[Hash],
           transactions: AVector[Transaction],
           target: BigInt,
           nonce: BigInt): Block = {
    // TODO: validate all the block dependencies; the first block dep should be previous block in the same chain
    val txsHash     = Hash.hash(transactions)
    val timestamp   = TimeStamp.now()
    val blockHeader = BlockHeader(blockDeps, txsHash, timestamp, target, nonce)
    Block(blockHeader, transactions)
  }

  def genesis(transactions: AVector[Transaction], target: BigInt, nonce: BigInt): Block = {
    val txsHash     = Hash.hash(transactions)
    val blockHeader = BlockHeader.genesis(txsHash, target, nonce)
    Block(blockHeader, transactions)
  }
}
