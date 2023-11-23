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

package org.alephium.protocol.config

import org.alephium.protocol.model.HardFork
import org.alephium.util.{AlephiumSpec, Duration, TimeStamp}

class NetworkConfigSpec extends AlephiumSpec with NetworkConfigFixture.Default {
  override def lemanHardForkTimestamp: TimeStamp = TimeStamp.now()
  override def ghostHardForkTimestamp: TimeStamp = lemanHardForkTimestamp.plusMinutesUnsafe(1)

  it should "get hard fork" in {
    networkConfig.getHardFork(lemanHardForkTimestamp) is HardFork.Leman
    networkConfig.getHardFork(
      lemanHardForkTimestamp.minusUnsafe(Duration.ofSecondsUnsafe(1))
    ) is HardFork.Mainnet
    networkConfig.getHardFork(
      lemanHardForkTimestamp.plusUnsafe(Duration.ofSecondsUnsafe(1))
    ) is HardFork.Leman
    networkConfig.getHardFork(ghostHardForkTimestamp) is HardFork.Ghost
    networkConfig.getHardFork(
      ghostHardForkTimestamp.minusUnsafe(Duration.ofSecondsUnsafe(1))
    ) is HardFork.Leman
    networkConfig.getHardFork(
      ghostHardForkTimestamp.plusUnsafe(Duration.ofSecondsUnsafe(1))
    ) is HardFork.Ghost
  }
}
