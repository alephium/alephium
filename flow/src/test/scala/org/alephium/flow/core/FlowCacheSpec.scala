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

import org.alephium.flow.FlowFixture
import org.alephium.protocol.model.{ChainIndex, GroupIndex}
import org.alephium.util.{AlephiumSpec, LockFixture, TimeStamp}

class FlowCacheSpec extends AlephiumSpec with LockFixture {
  it should "remove blocks when the cache is full" in new FlowFixture {
    override val configValues = Map(
      ("alephium.broker.broker-num", 1),
      ("alephium.consensus.block-cache-capacity-per-chain", 1)
    )
    consensusConfig.blockCacheCapacityPerChain is 1

    val chainIndex = ChainIndex.unsafe(0, 1)
    val cache      = blockFlow.getGroupCache(GroupIndex.unsafe(0))

    val blockFlow1 = isolatedBlockFlow()
    val blocks = (0 until 3).map { _ =>
      val block = emptyBlock(blockFlow1, chainIndex)
      addAndCheck(blockFlow1, block)
      block
    }
    val block1 = emptyBlock(blockFlow, ChainIndex.unsafe(1, 0))

    blockFlow.cacheBlock(blocks(1))
    cache.get(blocks(1).hash).nonEmpty is true
    blockFlow.cacheBlock(block1)
    cache.get(blocks(1).hash).nonEmpty is true
    cache.get(block1.hash).nonEmpty is true
    blockFlow.cacheBlock(blocks(0))
    cache.get(blocks(0).hash).nonEmpty is false
    cache.get(blocks(1).hash).nonEmpty is true
    cache.get(block1.hash).nonEmpty is true
    blockFlow.cacheBlock(blocks(2))
    cache.get(blocks(1).hash).nonEmpty is false
    cache.get(blocks(2).hash).nonEmpty is true
    cache.get(block1.hash).nonEmpty is true
  }

  it should "remove headers when the cache is full" in new FlowFixture {
    val header = emptyBlock(blockFlow, ChainIndex.unsafe(0, 1)).header

    val cache = FlowCache.headers(1)
    cache.put(header.hash, header)
    cache.get(header.hash) is Some(header)
    val header1 = header.copy(timestamp = TimeStamp.zero)
    cache.put(header1.hash, header1)
    cache.get(header.hash) is Some(header)
    cache.get(header1.hash) is None
    val header2 = header.copy(timestamp = TimeStamp.now().plusSecondsUnsafe(1))
    cache.put(header2.hash, header2)
    cache.get(header.hash) is None
    cache.get(header2.hash) is Some(header2)
  }

  it should "use rw lock" in new FlowFixture with WithLock {
    val header  = emptyBlock(blockFlow, ChainIndex.unsafe(0, 1)).header
    val header1 = header.copy(timestamp = TimeStamp.now())
    val cache   = FlowCache.headers(1)
    val rwl     = cache._getLock

    checkWriteLock(rwl)(0, { cache.put(header.hash, header); cache.size }, 1)
    checkReadLock(rwl)(header1, cache.get(header.hash).get, header)
  }

  it should "not cache a header twice" in new FlowFixture {
    val header = emptyBlock(blockFlow, ChainIndex.unsafe(0, 1)).header
    val cache  = FlowCache.headers(1)
    cache.size is 0
    cache.put(header.hash, header)
    cache.size is 1
    cache.put(header.hash, header)
    cache.size is 1
  }
}
