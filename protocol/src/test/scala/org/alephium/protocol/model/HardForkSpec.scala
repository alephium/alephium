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

import org.alephium.util.AlephiumSpec

class HardForkSpec extends AlephiumSpec {
  it should "compare hard fork version" in {
    (HardFork.Leman > HardFork.Mainnet) is true
    (HardFork.Rhone > HardFork.Leman) is true
    (HardFork.Rhone > HardFork.Mainnet) is true
    (HardFork.Danube > HardFork.Mainnet) is true
    (HardFork.Danube > HardFork.Leman) is true
    (HardFork.Danube > HardFork.Rhone) is true

    HardFork.Mainnet.version is 0
    HardFork.Leman.version is 1
    HardFork.Rhone.version is 2
    HardFork.Danube.version is 3

    HardFork.Leman.isLemanEnabled() is true
    HardFork.Leman.isRhoneEnabled() is false
    HardFork.Leman.isDanubeEnabled() is false
    HardFork.Mainnet.isLemanEnabled() is false
    HardFork.Mainnet.isRhoneEnabled() is false
    HardFork.Mainnet.isDanubeEnabled() is false
    HardFork.Rhone.isRhoneEnabled() is true
    HardFork.Rhone.isDanubeEnabled() is false
    HardFork.Danube.isDanubeEnabled() is true

    Seq(HardFork.Leman, HardFork.Rhone, HardFork.Danube).contains(
      HardFork.SinceLemanForTest
    ) is true
  }
}
