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

package org.alephium.flow.network.broker

import org.alephium.protocol.Generators
import org.alephium.util.{AlephiumSpec, Duration, TimeStamp}

class InMemoryMisbehaviorStorageSpec extends AlephiumSpec with Generators {
  it should "check until for isBanned" in {
    val storage = new InMemoryMisbehaviorStorage(Duration.ofSecondsUnsafe(10))
    val address = socketAddressGen.sample.get.getAddress
    val until   = TimeStamp.now().plusUnsafe(Duration.ofMillisUnsafe(100))

    storage.ban(address, until)
    storage.isBanned(address) is true

    Thread.sleep(100)
    storage.isBanned(address) is false
  }

}
