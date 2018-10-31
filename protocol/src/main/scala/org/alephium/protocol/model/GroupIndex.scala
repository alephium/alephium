package org.alephium.protocol.model

import org.alephium.serde.Serde
import org.alephium.protocol.config.ConsensusConfig

class GroupIndex private (val value: Int) extends AnyVal {
  override def toString: String = s"GroupIndex($value)"
}

object GroupIndex {
  def apply(value: Int)(implicit config: ConsensusConfig): GroupIndex = {
    val index = new GroupIndex(value)
    assert(validate(index, config.groups))
    index
  }

  def unsafe(value: Int): GroupIndex                    = new GroupIndex(value)
  def validate(group: GroupIndex, groups: Int): Boolean = 0 <= group.value && group.value < groups

  implicit val serde: Serde[GroupIndex] = Serde.IntSerde.xmap[GroupIndex](unsafe(_), _.value)
}
