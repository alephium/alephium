package org.alephium.protocol.model

import org.alephium.crypto._
import org.alephium.protocol.config.ConsensusConfig
import org.alephium.serde._
import org.alephium.util.AVector

case class BlockHeader(
    blockDeps: AVector[Keccak256],
    txsHash: Keccak256,
    timestamp: Long,
    target: BigInt,
    nonce: BigInt
) extends Keccak256Hash[BlockHeader] {

  override val hash: Keccak256 = Keccak256.hash(serialize[BlockHeader](this))

  def chainIndex(implicit config: ConsensusConfig): ChainIndex = {
    ChainIndex.from(hash)
  }

  def parentHash(implicit config: ConsensusConfig): Keccak256 = {
    uncleHash(chainIndex.to)
  }

  // when toIndex == chainIndex.to, it returns hash of parent
  def uncleHash(toIndex: GroupIndex)(implicit config: ConsensusConfig): Keccak256 = {
    assert(toIndex == chainIndex.to)
    blockDeps.takeRight(config.groups)(toIndex.value)
  }
}

object BlockHeader {

  implicit val serde: Serde[BlockHeader] =
    Serde.forProduct5(apply, bh => (bh.blockDeps, bh.txsHash, bh.timestamp, bh.target, bh.nonce))
}
