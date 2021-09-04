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

import org.alephium.protocol.model.NoIndexModelGenerators
import org.alephium.util.{AlephiumSpec, LockFixture}

class MultiChainSpec extends AlephiumSpec with NoIndexModelGenerators with LockFixture {
  it should "work as cache with capacity" in {
    val cache  = MultiChain.bodyVerifyingBlocks(2)
    val blocks = (0 until 3).map(_ => blockGen.sample.get)
    cache.put(blocks(0))
    cache.put(blocks(1))
    cache.get(blocks(0).hash).get is blocks(0)
    cache.get(blocks(1).hash).get is blocks(1)
    cache.put(blocks(2))
    cache.get(blocks(0).hash) is None
    cache.get(blocks(1).hash).get is blocks(1)
    cache.get(blocks(2).hash).get is blocks(2)
  }

  it should "use rw lock" in new WithLock {
    val cache    = MultiChain.bodyVerifyingBlocks(2)
    lazy val rwl = cache._getLock
    val block0   = blockGen.sample.get
    val block1   = blockGen.sample.get

    checkWriteLock(rwl)(0, { cache.put(block0); cache.cache.size }, 1)
    checkReadLock(rwl)(block1, cache.get(block0.hash).get, block0)
  }
}
