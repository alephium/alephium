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

import scala.concurrent.duration.DurationInt
import scala.util.Random

import org.apache.pekko.actor.Props
import org.apache.pekko.io.Tcp
import org.apache.pekko.testkit.{TestActorRef, TestProbe}
import org.apache.pekko.util.ByteString

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.protocol.model.ModelGenerators
import org.alephium.serde.Staging

class BrokerConnectorSpec extends AlephiumFlowActorSpec with InfoFixture with ModelGenerators {
  private def createBrokerConnector(
      connection: TestProbe,
      cliqueCoordinator: TestProbe,
      terminateSystemProbe: TestProbe
  ): TestActorRef[BrokerConnector] = {
    val remoteAddress = socketAddressGen.sample.get
    TestActorRef[BrokerConnector](
      Props(
        new BrokerConnector(remoteAddress, connection.ref, cliqueCoordinator.ref) {
          override def terminateSystem(): Unit = {
            terminateSystemProbe.ref ! "terminate-system"
            context.stop(self)
          }
        }
      )
    )
  }

  private def disconnectWithoutTerminatingSystem(
      brokerConnector: TestActorRef[BrokerConnector],
      terminateSystemProbe: TestProbe
  ): Unit = {
    watch(brokerConnector)
    system.stop(brokerConnector.underlyingActor.connectionHandler.ref)
    expectTerminated(brokerConnector)
    terminateSystemProbe.expectNoMessage(100.millis)
  }

  it should "follow this workflow" in {
    val connection        = TestProbe()
    val cliqueCoordinator = TestProbe()
    val brokerConnector =
      TestActorRef[BrokerConnector](
        BrokerConnector.props(socketAddressGen.sample.get, connection.ref, cliqueCoordinator.ref)
      )

    val randomId      = Random.nextInt(brokerConfig.brokerNum)
    val randomAddress = socketAddressGen.sample.get
    val randomInfo =
      PeerInfo.unsafe(
        randomId,
        brokerConfig.groupNumPerBroker,
        Some(randomAddress),
        randomAddress,
        Random.nextInt(),
        Random.nextInt()
      )

    connection.expectMsgType[Tcp.Register]
    watch(brokerConnector)

    brokerConnector ! BrokerConnector.Received(Message.Peer(randomInfo))
    cliqueCoordinator.expectMsgType[PeerInfo]

    val randomCliqueInfo = genIntraCliqueInfo
    brokerConnector ! BrokerConnector.Send(randomCliqueInfo)
    connection.expectMsg(Tcp.ResumeReading)
    connection.expectMsgPF() { case Tcp.Write(data, _) =>
      Message.deserialize(data) isE Staging(Message.Clique(randomCliqueInfo), ByteString.empty)
    }

    brokerConnector ! BrokerConnector.Received(Message.Ack(randomId))
    cliqueCoordinator.expectMsg(Message.Ack(randomId))

    val updatedCliqueInfo = genIntraCliqueInfo
    brokerConnector ! BrokerConnector.Send(updatedCliqueInfo)
    connection.expectMsgPF() { case Tcp.Write(data, _) =>
      Message.deserialize(data) isE Staging(Message.Clique(updatedCliqueInfo), ByteString.empty)
    }

    brokerConnector ! BrokerConnector.Received(Message.Ack(randomId))
    cliqueCoordinator.expectMsg(Message.Ack(randomId))
    brokerConnector ! CliqueCoordinator.Ready
    connection.expectMsgPF() { case Tcp.Write(data, _) =>
      Message.deserialize(data) isE Staging(Message.Ready, ByteString.empty)
    }

    system.stop(brokerConnector.underlyingActor.connectionHandler.ref)
    expectTerminated(brokerConnector)
  }

  it should "close safely when the connection terminates before PeerInfo" in {
    val connection           = TestProbe()
    val cliqueCoordinator    = TestProbe()
    val terminateSystemProbe = TestProbe()
    val brokerConnector =
      createBrokerConnector(connection, cliqueCoordinator, terminateSystemProbe)

    connection.expectMsgType[Tcp.Register]
    disconnectWithoutTerminatingSystem(brokerConnector, terminateSystemProbe)
  }

  it should "close safely when the connection terminates before clique broadcast" in {
    val connection           = TestProbe()
    val cliqueCoordinator    = TestProbe()
    val terminateSystemProbe = TestProbe()
    val brokerConnector =
      createBrokerConnector(connection, cliqueCoordinator, terminateSystemProbe)
    val remoteAddress = socketAddressGen.sample.get
    val peerInfo = PeerInfo.unsafe(
      (brokerConfig.brokerId + 1) % brokerConfig.brokerNum,
      brokerConfig.groupNumPerBroker,
      Some(remoteAddress),
      remoteAddress,
      0,
      0
    )

    connection.expectMsgType[Tcp.Register]
    brokerConnector ! BrokerConnector.Received(Message.Peer(peerInfo))
    cliqueCoordinator.expectMsg(peerInfo)
    disconnectWithoutTerminatingSystem(brokerConnector, terminateSystemProbe)
  }

  it should "close safely when the connection terminates before Ack" in {
    val connection           = TestProbe()
    val cliqueCoordinator    = TestProbe()
    val terminateSystemProbe = TestProbe()
    val brokerConnector =
      createBrokerConnector(connection, cliqueCoordinator, terminateSystemProbe)
    val remoteAddress = socketAddressGen.sample.get
    val peerInfo = PeerInfo.unsafe(
      (brokerConfig.brokerId + 1) % brokerConfig.brokerNum,
      brokerConfig.groupNumPerBroker,
      Some(remoteAddress),
      remoteAddress,
      0,
      0
    )

    connection.expectMsgType[Tcp.Register]
    brokerConnector ! BrokerConnector.Received(Message.Peer(peerInfo))
    cliqueCoordinator.expectMsg(peerInfo)
    brokerConnector ! BrokerConnector.Send(genIntraCliqueInfo)
    connection.expectMsg(Tcp.ResumeReading)
    connection.expectMsgType[Tcp.Write]
    disconnectWithoutTerminatingSystem(brokerConnector, terminateSystemProbe)
  }
}
