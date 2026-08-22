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

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.testkit.{SocketUtil, TestActorRef, TestProbe}

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.network.Bootstrapper
import org.alephium.protocol.SignatureSchema
import org.alephium.util.ActorRefT

class CliqueCoordinatorSpec extends AlephiumFlowActorSpec {
  private def peerInfo(id: Int): PeerInfo = {
    val address = SocketUtil.temporaryServerAddress()
    PeerInfo.unsafe(id, brokerConfig.groupNumPerBroker, Some(address), address, 0, 0)
  }

  private def addBroker(coordinator: ActorRef, id: Int): TestProbe = {
    val probe = TestProbe()
    coordinator.tell(peerInfo(id), probe.ref)
    probe
  }

  it should "await all the brokers" in {
    val bootstrapper                              = TestProbe()
    val (discoveryPrivateKey, discoveryPublicKey) = SignatureSchema.secureGeneratePriPub()
    val coordinator = system.actorOf(
      CliqueCoordinator.props(ActorRefT(bootstrapper.ref), discoveryPrivateKey, discoveryPublicKey)
    )

    val probs = (0 until brokerConfig.brokerNum)
      .filter(_ != brokerConfig.brokerId)
      .map { i =>
        val probe   = TestProbe()
        val address = SocketUtil.temporaryServerAddress()
        val peerInfo =
          PeerInfo.unsafe(i, brokerConfig.groupNumPerBroker, Some(address), address, 0, 0)
        coordinator.tell(peerInfo, probe.ref)
        (i, probe)
      }
      .toMap

    probs.values.foreach(_.expectMsgPF() { case BrokerConnector.Send(intraCliqueInfo) =>
      intraCliqueInfo.priKey is discoveryPrivateKey
    })

    bootstrapper.expectNoMessage(100.millis)

    probs.foreach { case (id, probe) =>
      coordinator.tell(Message.Ack(id), probe.ref)
    }
    bootstrapper.expectMsg(Bootstrapper.ForwardConnection)
    probs.values.foreach(_.expectMsgType[CliqueCoordinator.Ready.type])

    watch(coordinator)
    probs.values.foreach(p => system.stop(p.ref))

    bootstrapper.expectMsgPF() { case Bootstrapper.SendIntraCliqueInfo(intraCliqueInfo) =>
      intraCliqueInfo.priKey is discoveryPrivateKey
    }

    expectTerminated(coordinator)
  }

  it should "release disconnected brokers before bootstrap is ready" in {
    val bootstrapper                              = TestProbe()
    val (discoveryPrivateKey, discoveryPublicKey) = SignatureSchema.secureGeneratePriPub()
    val coordinator = TestActorRef[CliqueCoordinator](
      CliqueCoordinator.props(ActorRefT(bootstrapper.ref), discoveryPrivateKey, discoveryPublicKey)
    )
    val remoteIds = (0 until brokerConfig.brokerNum).filter(_ != brokerConfig.brokerId)
    remoteIds.length is 2
    val firstId  = remoteIds.head
    val secondId = remoteIds.last

    val disconnectedBeforeBroadcast = addBroker(coordinator, firstId)
    system.stop(disconnectedBeforeBroadcast.ref)
    eventually(coordinator.underlyingActor.brokerInfos(firstId).isEmpty is true)

    val first  = addBroker(coordinator, firstId)
    val second = addBroker(coordinator, secondId)
    first.expectMsgType[BrokerConnector.Send]
    second.expectMsgType[BrokerConnector.Send]
    bootstrapper.expectNoMessage(100.millis)

    coordinator.tell(Message.Ack(firstId), first.ref)
    system.stop(second.ref)
    eventually(
      coordinator.underlyingActor.brokerInfos(secondId).isEmpty &&
        !coordinator.underlyingActor.readys(firstId) is true
    )

    val secondReplacement = addBroker(coordinator, secondId)
    val updatedCliqueInfo = first.expectMsgType[BrokerConnector.Send].intraCliqueInfo
    secondReplacement.expectMsg(BrokerConnector.Send(updatedCliqueInfo))
    bootstrapper.expectNoMessage(100.millis)

    coordinator.tell(Message.Ack(firstId), first.ref)
    coordinator.tell(Message.Ack(secondId), secondReplacement.ref)
    bootstrapper.expectMsg(Bootstrapper.ForwardConnection)
    first.expectMsg(CliqueCoordinator.Ready)
    secondReplacement.expectMsg(CliqueCoordinator.Ready)

    watch(coordinator)
    system.stop(first.ref)
    system.stop(secondReplacement.ref)
    bootstrapper.expectMsg(Bootstrapper.SendIntraCliqueInfo(updatedCliqueInfo))
    expectTerminated(coordinator)
  }
}
