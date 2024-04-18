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
    (HardFork.Ghost > HardFork.Leman) is true
    (HardFork.Ghost > HardFork.Mainnet) is true
    HardFork.Mainnet.version is 0
    HardFork.Leman.version is 1
    HardFork.Ghost.version is 2

    HardFork.Leman.isLemanEnabled() is true
    HardFork.Leman.isGhostEnabled() is false
    HardFork.Mainnet.isLemanEnabled() is false
    HardFork.Mainnet.isGhostEnabled() is false
    HardFork.Ghost.isGhostEnabled() is true

    Seq(HardFork.Leman, HardFork.Ghost).contains(HardFork.SinceLemanForTest) is true
  }
}
