package org.alephium.protocol.model

import org.alephium.crypto._
import org.alephium.protocol.config.GroupConfig
import org.alephium.serde._
import org.alephium.util.AVector

case class BlockHeader(
    blockDeps: AVector[Keccak256],
    txsHash: Keccak256,
    timestamp: Long,
    target: BigInt,
    nonce: BigInt
) extends Keccak256Hash[BlockHeader] {

  override val hash: Keccak256 = _getHash

  def chainIndex(implicit config: GroupConfig): ChainIndex = {
    ChainIndex.from(hash)
  }

  def parentHash(implicit config: GroupConfig): Keccak256 = {
    uncleHash(chainIndex.to)
  }

  // when toIndex == chainIndex.to, it returns hash of parent
  def uncleHash(toIndex: GroupIndex)(implicit config: GroupConfig): Keccak256 = {
    assert(toIndex == chainIndex.to)
    blockDeps.takeRight(config.groups)(toIndex.value)
  }

  def validateDiff: Boolean = {
    val current = BigInt(1, hash.bytes.toArray)
    assert(current >= 0)
    current <= target
  }

  def validateIndex(target: ChainIndex)(implicit config: GroupConfig): Boolean = {
    val actual = chainIndex
    target.from == actual.from && target.to == actual.to
  }

  // Note: the target is not validated here
  def preValidate(target: ChainIndex)(implicit config: GroupConfig): Boolean = {
    validateIndex(target) && validateDiff
  }
}

object BlockHeader {

  implicit val serde: Serde[BlockHeader] =
    Serde.forProduct5(apply, bh => (bh.blockDeps, bh.txsHash, bh.timestamp, bh.target, bh.nonce))
}
