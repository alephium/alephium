package org.alephium.protocol.model

import akka.util.ByteString
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

  def relateTo(group: Int): Boolean = {
    from == group || to == group
  }

  def toOneDim(implicit config: ConsensusConfig): Int = from * config.groups + to
}

object ChainIndex {

  def fromInt(n: Int)(implicit config: ConsensusConfig): ChainIndex = {
    val index = math.abs(n) % config.chainNum
    ChainIndex(index / config.groups, index % config.groups)
  }

  def fromHash(hash: Keccak256)(implicit config: ConsensusConfig): ChainIndex = {
    bytes2Index(hash.bytes)
  }

  def fromPeerId(peerId: PeerId)(implicit config: ConsensusConfig): ChainIndex = {
    bytes2Index(peerId.bytes)
  }

  private[ChainIndex] def hash2Index(hash: Keccak256)(implicit config: ConsensusConfig): Int = {
    val BigIndex = (hash.beforeLast & 0xFF) << 8 | (hash.last & 0xFF)
    BigIndex % config.chainNum
  }

  private[ChainIndex] def bytes2Index(bytes: ByteString)(
      implicit config: ConsensusConfig): ChainIndex = {
    assert(bytes.length >= 2)

    val beforeLast = bytes(bytes.length - 2)
    val last       = bytes.last
    val bigIndex   = (beforeLast & 0xFF) << 8 | (last & 0xFF)
    fromInt(bigIndex)
  }
}
