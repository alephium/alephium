package org.alephium.protocol.model

import org.alephium.crypto.Keccak256
import org.alephium.protocol.config.ConsensusConfig

case class ChainIndex(from: Int, to: Int) {

  def accept(block: Block)(implicit config: ConsensusConfig): Boolean = {
    val target = from * config.groups + to
    val actual = ChainIndex.hash2Index(block.hash)
    actual == target && {
      val current = BigInt(1, block.hash.bytes.toArray)
      current <= config.maxMiningTarget
    }
  }

  def toOneDim(implicit config: ConsensusConfig): Int = from * config.groups + to
}

object ChainIndex {

  def fromHash(hash: Keccak256)(implicit config: ConsensusConfig): ChainIndex = {
    val target = hash2Index(hash)
    val from   = target / config.groups
    val to     = target % config.groups
    ChainIndex(from, to)
  }

  private[ChainIndex] def hash2Index(hash: Keccak256)(implicit config: ConsensusConfig): Int = {
    val BigIndex = (hash.beforeLast & 0xFF) << 8 | (hash.last & 0xFF)
    BigIndex % config.chainNum
  }
}
