package org.alephium.protocol.model

import org.alephium.protocol.config.GroupConfig
import org.alephium.serde.RandomBytes
import org.alephium.util.Bytes

class ChainIndex(val from: GroupIndex, val to: GroupIndex) {
  def relateTo(brokerInfo: BrokerGroupInfo): Boolean = {
    brokerInfo.contains(from) || brokerInfo.contains(to)
  }

  def relateTo(groupIndex: GroupIndex): Boolean = {
    from == groupIndex || to == groupIndex
  }

  def isIntraGroup: Boolean = from == to

  def toOneDim(implicit config: GroupConfig): Int = from.value * config.groups + to.value

  override def equals(obj: Any): Boolean = obj match {
    case that: ChainIndex => from == that.from && to == that.to
    case _                => false
  }

  override def hashCode(): Int = {
    from.value ^ to.value
  }

  override def toString: String = s"ChainIndex(${from.value}, ${to.value})"
}

object ChainIndex {
  def from(from: Int, to: Int)(implicit config: GroupConfig): Option[ChainIndex] = {
    if (validate(from, to)) {
      Some(new ChainIndex(GroupIndex.unsafe(from), GroupIndex.unsafe(to)))
    } else None
  }

  def unsafe(index: Int)(implicit config: GroupConfig): ChainIndex = {
    assume(index >= 0 && index < config.chainNum)
    new ChainIndex(GroupIndex.unsafe(index / config.groups),
                   GroupIndex.unsafe(index % config.groups))
  }

  def unsafe(from: Int, to: Int)(implicit config: GroupConfig): ChainIndex = {
    assume(validate(from, to))
    new ChainIndex(GroupIndex.unsafe(from), GroupIndex.unsafe(to))
  }

  def apply(from: GroupIndex, to: GroupIndex): ChainIndex = {
    new ChainIndex(from, to)
  }

  @inline
  private def validate(from: Int, to: Int)(implicit config: GroupConfig): Boolean = {
    0 <= from && from < config.groups && 0 <= to && to < config.groups
  }

  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  def from(randomBytes: RandomBytes)(implicit config: GroupConfig): ChainIndex = {
    val bytes = randomBytes.bytes
    assume(bytes.length >= 2)

    val beforeLast = Bytes.toPosInt(bytes(bytes.length - 2))
    val last       = Bytes.toPosInt(bytes.last)
    val bigIndex   = beforeLast << 8 | last
    assume(bigIndex >= 0)

    val index = bigIndex % config.chainNum
    ChainIndex.unsafe(index / config.groups, index % config.groups)
  }
}
