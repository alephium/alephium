// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.protocol.model

import org.alephium.protocol.BlockHash
import org.alephium.protocol.config.GroupConfig
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

  override def equals(obj: Any): Boolean =
    obj match {
      case that: ChainIndex => from == that.from && to == that.to
      case _                => false
    }

  override def hashCode(): Int = {
    from.value ^ to.value
  }

  override def toString: String = s"ChainIndex(${from.value}, ${to.value})"

  def prettyString: String = s"chain: ${from.value}->${to.value}"
}

object ChainIndex {
  def from(from: Int, to: Int)(implicit config: GroupConfig): Option[ChainIndex] = {
    if (validate(from, to)) {
      Some(new ChainIndex(GroupIndex.unsafe(from), GroupIndex.unsafe(to)))
    } else {
      None
    }
  }

  def unsafe(index: Int)(implicit config: GroupConfig): ChainIndex = {
    assume(index >= 0 && index < config.chainNum)
    new ChainIndex(
      GroupIndex.unsafe(index / config.groups),
      GroupIndex.unsafe(index % config.groups)
    )
  }

  def unsafe(from: Int, to: Int)(implicit config: GroupConfig): ChainIndex = {
    assume(validate(from, to))
    new ChainIndex(GroupIndex.unsafe(from), GroupIndex.unsafe(to))
  }

  def apply(from: GroupIndex, to: GroupIndex): ChainIndex = {
    new ChainIndex(from, to)
  }

  @inline
  def validate(from: Int, to: Int)(implicit config: GroupConfig): Boolean = {
    0 <= from && from < config.groups && 0 <= to && to < config.groups
  }

  def from(hash: BlockHash)(implicit config: GroupConfig): ChainIndex = {
    from(hash, config.groups)
  }

  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  def from(hash: BlockHash, groups: Int): ChainIndex = {
    val bytes = hash.bytes
    assume(bytes.length >= 2)

    val beforeLast = Bytes.toPosInt(bytes(bytes.length - 2))
    val last       = Bytes.toPosInt(bytes.last)
    val bigIndex   = beforeLast << 8 | last
    assume(bigIndex >= 0)

    val chainNum = groups * groups
    val index    = bigIndex % chainNum
    new ChainIndex(new GroupIndex(index / groups), new GroupIndex(index % groups))
  }
}
