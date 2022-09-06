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

package org.alephium.flow.network.sync

import org.alephium.protocol.model.BlockHash
import org.alephium.util.{AlephiumSpec, AVector, Duration, TimeStamp}

class FetchStateSpec extends AlephiumSpec {
  trait Fixture {
    val maxCapacity      = 20
    val timeout          = Duration.ofSecondsUnsafe(2)
    val maxDownloadTimes = 2
    val fetchState       = FetchState[BlockHash](maxCapacity, timeout, maxDownloadTimes)
  }

  it should "update state" in new Fixture {
    val hash = BlockHash.generate
    (0 until maxDownloadTimes).foreach { _ =>
      fetchState.needToFetch(hash, TimeStamp.now()) is true
      fetchState.states.contains(hash) is true
    }
    fetchState.needToFetch(hash, TimeStamp.now()) is false
  }

  it should "cleanup cache based on capacity and timestamp" in new Fixture {
    val hash0 = BlockHash.generate
    fetchState.needToFetch(hash0, TimeStamp.now()) is true
    fetchState.states.contains(hash0) is true

    val hashes    = AVector.fill(maxCapacity)(BlockHash.generate)
    val timestamp = TimeStamp.now()
    hashes.foreach { hash =>
      fetchState.needToFetch(hash, timestamp) is true
      fetchState.states.contains(hash) is true
    }
    fetchState.states.contains(hash0) is false

    Thread.sleep(timeout.millis + 500)
    val hash1 = BlockHash.generate
    fetchState.needToFetch(hash1, TimeStamp.now()) is true
    hashes.foreach { hash =>
      fetchState.states.contains(hash) is false
    }
  }
}
