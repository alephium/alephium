package org.alephium.protocol.model

import org.alephium.protocol.config.ConsensusConfig

class GroupIndex private (val value: Int) extends AnyVal {
  override def toString: String = s"GroupIndex($value)"
}

object GroupIndex {
  def apply(value: Int)(implicit config: ConsensusConfig): GroupIndex = {
    assert(0 <= value && value < config.groups)
    new GroupIndex(value)
  }

  def unsafe(value: Int): GroupIndex = new GroupIndex(value)
}
