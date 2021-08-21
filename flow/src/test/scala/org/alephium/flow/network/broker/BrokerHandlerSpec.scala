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

package org.alephium.flow.network.broker

import java.net.InetSocketAddress

import akka.actor.Props
import akka.testkit.{SocketUtil, TestActorRef, TestProbe}

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.AllHandlers
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.SignatureSchema
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.message.{Hello, Payload, Pong, RequestId}
import org.alephium.protocol.model.{BrokerInfo, CliqueId}
import org.alephium.util.{ActorRefT, Duration}

class BrokerHandlerSpec extends AlephiumFlowActorSpec {
  it should "handshake with new connection" in new Fixture {
    val (priKey, pubKey) = SignatureSchema.secureGeneratePriPub()
    val brokerInfo =
      BrokerInfo.unsafe(CliqueId(pubKey), 0, 1, new InetSocketAddress("127.0.0.1", 0))
    val hello = Hello.unsafe(brokerInfo.interBrokerInfo, priKey)
    brokerHandler ! BrokerHandler.Received(hello)
    brokerHandler.underlyingActor.pingPongTickOpt is a[Some[_]]
  }

  it should "stop when handshake timeout" in new Fixture {
    watch(brokerHandler)
    brokerHandler ! BrokerHandler.HandShakeTimeout
    expectTerminated(brokerHandler)
  }

  it should "stop when received other message than handshake message" in new Fixture {
    watch(brokerHandler)
    brokerHandler ! BrokerHandler.Received(Pong(RequestId.unsafe(100)))
    expectTerminated(brokerHandler)
  }

  trait Fixture {
    val connectionHandler     = TestProbe()
    val blockFlowSynchronizer = TestProbe()
    val brokerHandler =
      TestActorRef[TestBrokerHandler](
        TestBrokerHandler.props(connectionHandler.ref, blockFlowSynchronizer.ref, blockFlow)
      )

    connectionHandler.expectMsgType[ConnectionHandler.Send]
    brokerHandler.underlyingActor.pingPongTickOpt is None
  }
}

object TestBrokerHandler {
  def props(
      brokerConnectionHandler: ActorRefT[ConnectionHandler.Command],
      blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command],
      blockflow: BlockFlow
  )(implicit brokerConfig: BrokerConfig, networkSetting: NetworkSetting): Props = {
    Props(new TestBrokerHandler(brokerConnectionHandler, blockFlowSynchronizer, blockflow))
  }
}

class TestBrokerHandler(
    val brokerConnectionHandler: ActorRefT[ConnectionHandler.Command],
    val blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command],
    val blockflow: BlockFlow
)(implicit val brokerConfig: BrokerConfig, val networkSetting: NetworkSetting)
    extends BrokerHandler {
  val connectionType: ConnectionType = OutboundConnection

  val (priKey, pubKey) = SignatureSchema.secureGeneratePriPub()

  override val remoteAddress: InetSocketAddress = SocketUtil.temporaryServerAddress()

  override def handShakeDuration: Duration = Duration.ofSecondsUnsafe(2)

  override def allHandlers: AllHandlers = ???

  val brokerInfo = BrokerInfo.unsafe(CliqueId(pubKey), 0, 1, new InetSocketAddress("127.0.0.1", 0))

  override val handShakeMessage: Payload = Hello.unsafe(brokerInfo.interBrokerInfo, priKey)

  override def exchanging: Receive = exchangingCommon

  override def dataOrigin: DataOrigin = ???

  override def pingFrequency: Duration = Duration.ofSecondsUnsafe(10)
}
