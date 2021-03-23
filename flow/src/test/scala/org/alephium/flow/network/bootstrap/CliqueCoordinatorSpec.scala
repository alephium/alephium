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

import akka.testkit.{SocketUtil, TestProbe}

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.network.Bootstrapper
import org.alephium.util.ActorRefT

class CliqueCoordinatorSpec extends AlephiumFlowActorSpec("CliqueCoordinatorSpec") {
  it should "await all the brokers" in {
    val bootstrapper = TestProbe()
    val coordinator  = system.actorOf(CliqueCoordinator.props(ActorRefT(bootstrapper.ref)))

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

    probs.values.foreach(_.expectMsgType[BrokerConnector.Send])

    bootstrapper.expectMsg(Bootstrapper.ForwardConnection)

    probs.foreach { case (id, probe) =>
      coordinator.tell(Message.Ack(id), probe.ref)
    }
    probs.values.foreach(_.expectMsgType[CliqueCoordinator.Ready.type])

    watch(coordinator)
    probs.values.foreach(p => system.stop(p.ref))

    bootstrapper.expectMsgType[Bootstrapper.SendIntraCliqueInfo]

    expectTerminated(coordinator)
  }
}
