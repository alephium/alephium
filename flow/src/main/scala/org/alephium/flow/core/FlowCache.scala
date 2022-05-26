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

package org.alephium.flow.core

import org.alephium.flow.core.BlockFlowState.BlockCache
import org.alephium.flow.model.BlockState
import org.alephium.protocol.BlockHash
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.BlockHeader
import org.alephium.util.{RWLock, ValueSortedMap}

object FlowCache {
  import org.alephium.util.Bytes.byteStringOrdering
  implicit val hashOrdering: Ordering[BlockHash] = Ordering.by(_.bytes)

  implicit val blockOrdering: Ordering[BlockCache] = Ordering.by(_.blockTime)
  def blocks(
      capacityPerChain: Int
  )(implicit brokerConfig: BrokerConfig): FlowCache[BlockHash, BlockCache] = {
    val m        = ValueSortedMap.empty[BlockHash, BlockCache]
    val capacity = (2 * brokerConfig.groups - 1) * capacityPerChain
    new FlowCache[BlockHash, BlockCache](m, capacity)
  }

  implicit val headerOrdering: Ordering[BlockHeader] = Ordering.by(_.timestamp)
  def headers(capacity: Int): FlowCache[BlockHash, BlockHeader] = {
    val m = ValueSortedMap.empty[BlockHash, BlockHeader]
    new FlowCache[BlockHash, BlockHeader](m, capacity)
  }

  implicit val stateOrdering: Ordering[BlockState] = Ordering.by(_.height)
  def states(capacity: Int): FlowCache[BlockHash, BlockState] = {
    val m = ValueSortedMap.empty[BlockHash, BlockState]
    new FlowCache[BlockHash, BlockState](m, capacity)
  }
}

class FlowCache[K, V](val underlying: ValueSortedMap[BlockHash, V], val capacity: Int)
    extends RWLock {
  def size: Int = readOnly(underlying.size)

  def exists(key: BlockHash): Boolean = readOnly(underlying.contains(key))

  def existsE[E](key: BlockHash)(genExists: => Either[E, Boolean]): Either[E, Boolean] = {
    if (exists(key)) {
      Right(true)
    } else {
      genExists
    }
  }

  def existsUnsafe(key: BlockHash)(genExists: => Boolean): Boolean = {
    exists(key) || genExists
  }

  def get(key: BlockHash): Option[V] = readOnly(underlying.get(key))

  def getE[E](key: BlockHash)(genValue: => Either[E, V]): Either[E, V] = {
    get(key) match {
      case Some(value) => Right(value)
      case None        => genValue
    }
  }

  def getUnsafe(key: BlockHash)(genValue: => V): V = {
    get(key) match {
      case Some(value) => value
      case None        => genValue
    }
  }

  def put(key: BlockHash, value: V): Unit = writeOnly {
    underlying.put(key, value)
    evict()
  }

  def evict(): Unit = {
    if (underlying.size > capacity) {
      underlying.remove(underlying.min)
      ()
    }
  }
}
