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

package org.alephium.flow.network.intraclique

import org.alephium.flow.FlowFixture
import org.alephium.protocol.model.{GroupIndex, ModelGenerators}
import org.alephium.util.{AlephiumSpec, AVector}

class BrokerHandlerSpec extends AlephiumSpec {
  it should "compute the headers and blocks for sync" in new FlowFixture with ModelGenerators {
    override val configValues = Map(("alephium.broker.broker-id", 1))

    config.broker.brokerNum is 3
    config.broker.groupNumPerBroker is 1
    config.broker.brokerId is 1

    val blocks0 = AVector.tabulate(groups0) { _ =>
      blockGenOf(GroupIndex.unsafe(0)).sample.get
    }
    val hashes0 = blocks0.map(_.hash).map(AVector(_))
    BrokerHandler.extractToSync(
      blockFlow,
      hashes0,
      config.broker.copy(brokerId = 0)
    ) is
      ((hashes0(0) ++ hashes0(2)) -> hashes0(1))
    val blocks2 = AVector.tabulate(groups0) { _ =>
      blockGenOf(GroupIndex.unsafe(2)).sample.get
    }
    val hashes2 = blocks2.map(_.hash).map(AVector(_))
    BrokerHandler.extractToSync(blockFlow, hashes2, config.broker.copy(brokerId = 2)) is
      ((hashes2(0) ++ hashes2(2)) -> hashes2(1))
  }
}
