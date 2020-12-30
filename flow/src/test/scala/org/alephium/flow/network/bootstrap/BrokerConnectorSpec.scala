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

package org.alephium.flow.network.bootstrap

import akka.io.Tcp
import akka.testkit.{TestActorRef, TestProbe}
import akka.util.ByteString

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.protocol.model.ModelGenerators
import org.alephium.serde.Staging
import org.alephium.util.Random

class BrokerConnectorSpec
    extends AlephiumFlowActorSpec("BrokerConnector")
    with InfoFixture
    with ModelGenerators {
  it should "follow this workflow" in {
    val connection        = TestProbe()
    val cliqueCoordinator = TestProbe()
    val brokerConnector =
      TestActorRef[BrokerConnector](
        BrokerConnector.props(socketAddressGen.sample.get, connection.ref, cliqueCoordinator.ref))

    val randomId      = Random.source.nextInt(brokerConfig.brokerNum)
    val randomAddress = socketAddressGen.sample.get
    val randomInfo =
      PeerInfo.unsafe(randomId,
                      brokerConfig.groupNumPerBroker,
                      Some(randomAddress),
                      randomAddress,
                      Random.source.nextInt,
                      Random.source.nextInt)

    connection.expectMsgType[Tcp.Register]
    watch(brokerConnector)

    brokerConnector ! BrokerConnector.Received(Message.Peer(randomInfo))
    cliqueCoordinator.expectMsgType[PeerInfo]

    val randomCliqueInfo = genIntraCliqueInfo
    brokerConnector ! BrokerConnector.Send(randomCliqueInfo)
    connection.expectMsg(Tcp.ResumeReading)
    connection.expectMsgPF() {
      case Tcp.Write(data, _) =>
        Message.deserialize(data) isE Staging(Message.Clique(randomCliqueInfo), ByteString.empty)
    }

    brokerConnector ! BrokerConnector.Received(Message.Ack(randomId))
    brokerConnector ! CliqueCoordinator.Ready
    connection.expectMsgPF() {
      case Tcp.Write(data, _) =>
        Message.deserialize(data) isE Staging(Message.Ready, ByteString.empty)
    }

    system.stop(brokerConnector.underlyingActor.connectionHandler.ref)
    expectTerminated(brokerConnector)
  }
}
