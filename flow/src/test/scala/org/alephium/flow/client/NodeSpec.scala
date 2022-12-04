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

package org.alephium.flow.client

import org.alephium.flow.FlowFixture
import org.alephium.protocol.ALPH
import org.alephium.util.AlephiumSpec

class NodeSpec extends AlephiumSpec {
  it should "check genesis blocks" in {
    val defaultFixture   = new FlowFixture {}
    val defaultConfig    = defaultFixture.config
    val defaultBlockFlow = defaultFixture.blockFlow
    val anotherFixture = new FlowFixture {
      override val genesisBalance = ALPH.alph(1)
    }
    val anotherConfig    = anotherFixture.config
    val anotherBlockFlow = anotherFixture.blockFlow

    Node.checkGenesisBlocks(defaultBlockFlow)(defaultConfig)
    intercept[Exception](Node.checkGenesisBlocks(defaultBlockFlow)(anotherConfig)).getMessage is
      Node.invalidGenesisBlockMsg
    Node.checkGenesisBlocks(anotherBlockFlow)(anotherConfig)
    intercept[Exception](Node.checkGenesisBlocks(anotherBlockFlow)(defaultConfig)).getMessage is
      Node.invalidGenesisBlockMsg
  }
}
