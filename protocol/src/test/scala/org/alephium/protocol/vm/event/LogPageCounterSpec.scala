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

package org.alephium.protocol.vm.event

import org.alephium.io.Inserted
import org.alephium.protocol.model.ContractId
import org.alephium.util.AlephiumSpec

class LogPageCounterSpec extends AlephiumSpec with Fixture {
  it should "cache initial counters" in {
    val cachedCounter: CachedLogPageCounter[ContractId] =
      newCachedLog(newDBStorage()).eventLogPageCounter
    val key0 = ContractId.random
    val key1 = ContractId.random
    cachedCounter.counter.put(key0, 100)
    cachedCounter.getInitialCount(key0) isE 100
    cachedCounter.counter.caches(key0) is Inserted(100)
    cachedCounter.initialCounts(key0) is 100

    cachedCounter.getInitialCount(key1) isE 0
    cachedCounter.counter.caches.contains(key1) is false
    cachedCounter.initialCounts(key1) is 0
  }

  it should "reuse initial counters for StagingLogPageCounter" in {
    val cachedCounter: CachedLogPageCounter[ContractId] =
      newCachedLog(newDBStorage()).eventLogPageCounter
    val stagingCounter: StagingLogPageCounter[ContractId] = cachedCounter.staging()

    val key0 = ContractId.random
    val key1 = ContractId.random
    cachedCounter.getInitialCount(key0)
    stagingCounter.getInitialCount(key1)
    cachedCounter.initialCounts(key0) is 0
    cachedCounter.initialCounts(key1) is 0
    cachedCounter.getInitialCount(key0).rightValue is 0
    stagingCounter.getInitialCount(key1).rightValue is 0

    cachedCounter.counter.put(key0, 1) isE ()
    cachedCounter.initialCounts(key0) is 0
    cachedCounter.getInitialCount(key0).rightValue is 0
  }
}
