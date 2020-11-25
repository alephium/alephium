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

import akka.io.{IO, Tcp}
import akka.testkit.{SocketUtil, TestProbe}
import akka.util.ByteString

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.network.Bootstrapper
import org.alephium.serde.Staging
import org.alephium.util.ActorRefT

class BrokerSpec extends AlephiumFlowActorSpec("BrokerSpec") with InfoFixture {
  it should "follow this workflow" in {
    val connection         = TestProbe()
    val bootstrapper       = TestProbe()
    val coordinatorAddress = SocketUtil.temporaryServerAddress()

    IO(Tcp) ! Tcp.Bind(connection.ref, coordinatorAddress)
    expectMsgType[Tcp.Bound]

    val broker = system.actorOf(
      Broker.props(ActorRefT[Bootstrapper.Command](bootstrapper.ref))(
        brokerConfig,
        networkSetting.copy(coordinatorAddress = coordinatorAddress)))
    watch(broker)

    connection.expectMsgPF() {
      case Tcp.Connected(_, expectedMasterAddress) =>
        expectedMasterAddress is coordinatorAddress
    }
    connection.reply(Tcp.Register(connection.ref))

    connection.expectMsgPF() {
      case Tcp.Received(data) =>
        Message.deserialize(data) isE Staging(Message.Peer(PeerInfo.self), ByteString.empty)
    }

    val randomInfo = genIntraCliqueInfo
    broker.tell(Broker.Received(Message.Clique(randomInfo)), connection.ref)
    connection.expectMsgPF() {
      case Tcp.Received(data) =>
        Message.deserialize(data) isE Staging(Message.Ack(brokerConfig.brokerId), ByteString.empty)
    }

    broker.tell(Broker.Received(Message.Ready), connection.ref)
    connection.expectMsg(Tcp.PeerClosed)

    bootstrapper.expectMsg(Bootstrapper.SendIntraCliqueInfo(randomInfo))
    expectTerminated(broker)
  }
}
