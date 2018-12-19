package org.alephium.protocol.model

import org.alephium.protocol.config.ConsensusConfig
import org.alephium.serde.RandomBytes

class ChainIndex private (val from: GroupIndex, val to: GroupIndex) {
  def validateDiff(header: BlockHeader)(implicit config: ConsensusConfig): Boolean = {
    val actual = header.chainIndex
    from == actual.from && to == actual.to && {
      val current = BigInt(1, header.hash.bytes.toArray)
      current <= config.maxMiningTarget
    }
  }

  def validateDiff(block: Block)(implicit config: ConsensusConfig): Boolean = {
    validateDiff(block.header)
  }

  def relateTo(groupIndex: GroupIndex): Boolean = {
    from == groupIndex || to == groupIndex
  }

  def toOneDim(implicit config: ConsensusConfig): Int = from.value * config.groups + to.value

  override def equals(obj: Any): Boolean = obj match {
    case that: ChainIndex => from == that.from && to == that.to
    case _                => false
  }

  override def hashCode(): Int = {
    from.value ^ to.value
  }

  override def toString: String = s"ChainIndex(${from.value}, ${to.toString})"
}

object ChainIndex {

  def apply(from: Int, to: Int)(implicit config: ConsensusConfig): ChainIndex = {
    assert(0 <= from && from < config.groups && 0 <= to && to < config.groups)
    new ChainIndex(GroupIndex(from), GroupIndex(to))
  }

  def apply(from: GroupIndex, to: GroupIndex): ChainIndex = {
    new ChainIndex(from, to)
  }

  def unsafe(from: Int, to: Int): ChainIndex =
    new ChainIndex(GroupIndex.unsafe(from), GroupIndex.unsafe(to))

  def from(randomBytes: RandomBytes)(implicit config: ConsensusConfig): ChainIndex = {
    val bytes = randomBytes.bytes
    assert(bytes.length >= 2)

    val beforeLast = bytes(bytes.length - 2)
    val last       = bytes.last
    val bigIndex   = (beforeLast & 0xFF) << 8 | (last & 0xFF)
    assert(bigIndex >= 0)

    val index = bigIndex % config.chainNum
    ChainIndex(index / config.groups, index % config.groups)
  }
}
