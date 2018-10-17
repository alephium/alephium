package org.alephium.flow.model

import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.flow.constant.Consensus
import org.alephium.protocol.model.Block

case class ChainIndex(from: Int, to: Int) {
  def accept(block: Block)(implicit config: PlatformConfig): Boolean = accept(block.hash)

  def accept(hash: Keccak256)(implicit config: PlatformConfig): Boolean = {
    val target     = from * config.groups + to
    val miningHash = Block.toMiningHash(hash)
    val actual     = ChainIndex.hash2Index(miningHash)
    actual == target && {
      val current = BigInt(1, miningHash.bytes.toArray)
      current <= Consensus.maxMiningTarget
    }
  }

  def toOneDim(implicit config: PlatformConfig): Int = from * config.groups + to
}

object ChainIndex {
  def fromHash(hash: Keccak256)(implicit config: PlatformConfig): ChainIndex = {
    val miningHash = Block.toMiningHash(hash)
    val target     = hash2Index(miningHash)
    val from       = target / config.groups
    val to         = target % config.groups
    ChainIndex(from, to)
  }

  private[ChainIndex] def hash2Index(hash: Keccak256)(implicit config: PlatformConfig): Int = {
    val BigIndex = (hash.beforeLast & 0xFF) << 8 | (hash.last & 0xFF)
    BigIndex % config.chainNum
  }
}
