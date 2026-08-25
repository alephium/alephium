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

package org.alephium.flow.network.interclique

import org.apache.pekko.io.Tcp
import org.apache.pekko.testkit.{TestActorRef, TestProbe}

import org.alephium.flow.{AlephiumFlowActorSpec, FlowFixture}
import org.alephium.flow.handler.TestUtils
import org.alephium.flow.network.SyncPeerProfile
import org.alephium.flow.network.broker.BrokerHandler
import org.alephium.protocol.{Generators, Signature}
import org.alephium.protocol.message.Hello
import org.alephium.protocol.model.InterBrokerInfo
import org.alephium.util.{ActorRefT, TimeStamp}

class OutboundBrokerHandlerSpec extends AlephiumFlowActorSpec {
  it should "connect to remote broker with valid broker info" in new Fixture {
    brokerHandler ! Tcp.Connected(
      expectedRemoteBroker.address,
      Generators.socketAddressGen.sample.get
    )
    val hello = helloWithVersion(expectedRemoteBroker.interBrokerInfo)
    brokerHandler ! BrokerHandler.Received(hello)
    brokerHandlerActor.pingPongTickOpt is a[Some[_]]
  }

  it should "not connect to remote broker with invalid broker info" in new Fixture {
    val handlerProbe = TestProbe()
    handlerProbe.watch(brokerHandler)
    brokerHandler ! Tcp.Connected(
      expectedRemoteBroker.address,
      Generators.socketAddressGen.sample.get
    )
    val wrongBroker = InterBrokerInfo.unsafe(
      Generators.cliqueIdGen.sample.get,
      expectedRemoteBroker.brokerId,
      expectedRemoteBroker.brokerNum
    )
    val hello = helloWithVersion(wrongBroker)
    brokerHandler ! BrokerHandler.Received(hello)
    handlerProbe.expectTerminated(brokerHandler.ref)
  }

  trait Fixture extends FlowFixture {
    val cliqueManager         = TestProbe()
    val connectionHandler     = TestProbe()
    val blockFlowSynchronizer = TestProbe()
    val maxForkDepth          = 5
    val expectedRemoteBroker  = Generators.brokerInfoGen.sample.get

    lazy val (allHandler, allHandlerProbes) = TestUtils.createAllHandlersProbe
    lazy val brokerHandler = TestActorRef[OutboundBrokerHandler](
      OutboundBrokerHandler.props(
        Generators.cliqueInfoGen.sample.get,
        expectedRemoteBroker,
        blockFlow,
        allHandler,
        ActorRefT(cliqueManager.ref),
        ActorRefT(blockFlowSynchronizer.ref)
      )
    )
    lazy val brokerHandlerActor = brokerHandler.underlyingActor

    def helloWithVersion(brokerInfo: InterBrokerInfo): Hello = {
      val clientId = s"scala-alephium/${SyncPeerProfile.FastSyncMinVersion}/Linux/p2p-v2"
      Hello.unsafe(clientId, TimeStamp.now(), brokerInfo, Signature.zero)
    }
  }
}
