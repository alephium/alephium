package org.alephium.protocol.model

import org.alephium.protocol.config.GroupConfig

class GroupIndex private (val value: Int) extends AnyVal {
  override def toString: String = s"GroupIndex($value)"
}

object GroupIndex {
  def apply(value: Int)(implicit config: GroupConfig): GroupIndex = {
    assert(validate(value))
    new GroupIndex(value)
  }

  def unsafe(value: Int): GroupIndex = new GroupIndex(value)
  def validate(group: Int)(implicit config: GroupConfig): Boolean =
    0 <= config.groups && group < config.groups
}
