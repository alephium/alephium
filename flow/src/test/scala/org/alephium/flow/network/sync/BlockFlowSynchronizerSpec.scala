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

import akka.testkit.{TestActorRef, TestProbe}
import org.scalatest.concurrent.Eventually

import org.alephium.flow.FlowFixture
import org.alephium.flow.handler.TestUtils
import org.alephium.protocol.Generators
import org.alephium.util.AlephiumActorSpec

class BlockFlowSynchronizerSpec extends AlephiumActorSpec("BlockFlowSynchronizer") {
  trait Fixture extends FlowFixture with Generators with Eventually {
    val (allHandlers, _) = TestUtils.createAllHandlersProbe
    val blockFlowSynchronizer = TestActorRef[BlockFlowSynchronizer](
      BlockFlowSynchronizer.props(blockFlow, allHandlers)
    )
    val blockFlowSynchronizerActor = blockFlowSynchronizer.underlyingActor
  }

  it should "add/remove brokers" in new Fixture {
    blockFlowSynchronizerActor.brokerInfos.isEmpty is true

    val probe  = TestProbe()
    val broker = brokerInfoGen.sample.get
    probe.send(blockFlowSynchronizer, BlockFlowSynchronizer.HandShaked(broker))
    eventually(blockFlowSynchronizerActor.brokerInfos.contains(probe.ref) is true)

    system.stop(probe.ref)
    eventually(blockFlowSynchronizerActor.brokerInfos.isEmpty is true)
  }
}
