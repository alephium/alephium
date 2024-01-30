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

import org.alephium.protocol.model.BlockHash
import org.alephium.util.{AlephiumSpec, AVector, LockFixture}

class HashesCacheSpec extends AlephiumSpec with LockFixture {
  it should "cache block hashes" in {
    val cache = HashesCache(2)
    cache.size is 0

    val hashes0 = AVector.fill(2)(BlockHash.random)
    cache.put(1, hashes0)
    cache.get(1) is Some(hashes0)
    cache.size is 1

    val hashes1 = BlockHash.random +: hashes0
    cache.put(1, hashes1)
    cache.get(1) is Some(hashes1)
    cache.size is 1
  }

  it should "remove hashes when the cache is full" in {
    val cache = HashesCache(2)
    cache.size is 0

    val hashes0 = AVector.fill(2)(BlockHash.random)
    cache.put(3, hashes0)
    cache.size is 1

    val hashes1 = AVector(BlockHash.random)
    cache.put(4, hashes1)
    cache.size is 2

    val hashes2 = AVector(BlockHash.random)
    cache.put(5, hashes2)
    cache.size is 2

    cache.get(3) is None
    cache.get(4) is Some(hashes1)
    cache.get(5) is Some(hashes2)
  }

  it should "use rw lock" in new WithLock {
    val hashes0 = AVector(BlockHash.random)
    val hashes1 = AVector(BlockHash.random)
    val cache   = HashesCache(1)
    val rwl     = cache._getLock

    checkWriteLock(rwl)(0, { cache.put(1, hashes0); cache.size }, 1)
    checkReadLock(rwl)(hashes1, cache.get(1).get, hashes0)
  }
}
