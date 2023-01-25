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

package org.alephium.flow.network

import akka.io.Tcp
import akka.testkit.TestProbe

import org.alephium.flow.FlowFixture
import org.alephium.flow.handler.TestUtils
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.bootstrap.InfoFixture
import org.alephium.flow.network.broker.{BrokerHandler, InboundConnection, OutboundConnection}
import org.alephium.protocol.message.{Message, NewBlock, NewHeader, RequestId, TxsResponse}
import org.alephium.protocol.model.{BrokerInfo, ChainIndex}
import org.alephium.util.{ActorRefT, AlephiumActorSpec, AVector}

class IntraCliqueManagerSpec extends AlephiumActorSpec {
  it should "sync with other brokers" in new Fixture {
    override val configValues: Map[String, Any] = Map(
      "alephium.broker.broker-id" -> 1
    )

    brokerConfig.brokerNum is 3
    brokerConfig.brokerId is 1

    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[TcpController.ConnectTo])

    val brokerInfos = (0 until brokerConfig.brokerNum).map { idx =>
      BrokerInfo.unsafe(
        cliqueInfo.id,
        idx,
        brokerConfig.brokerNum,
        cliqueInfo.intraBrokers(idx).address
      )
    }

    intraCliqueManager ! Tcp.Connected(brokerInfos(0).address, socketAddressGen.sample.get)
    listener.expectMsgPF() { case TcpController.ConnectTo(remoteAddress, _) =>
      remoteAddress is brokerInfos(2).address
    }

    val inboundConnection  = TestProbe()
    val outboundConnection = TestProbe()
    val clientInfo: String = "v0.0.0"
    inboundConnection.send(
      intraCliqueManager,
      IntraCliqueManager.HandShaked(brokerInfos(0), InboundConnection, clientInfo)
    )
    outboundConnection.send(
      intraCliqueManager,
      IntraCliqueManager.HandShaked(brokerInfos(2), OutboundConnection, clientInfo)
    )
    cliqueManagerProbe.expectMsg(IntraCliqueManager.Ready)

    info("Broadcast Blocks")

    intraCliqueManager ! broadcastBlockMsg
    inboundConnection.expectMsg(BrokerHandler.Send(broadcastBlockMsg.headerMsg))
    outboundConnection.expectMsg(BrokerHandler.Send(broadcastBlockMsg.headerMsg))

    val message1 = broadcastBlockMsg.copy(origin = DataOrigin.IntraClique(brokerInfos(0)))
    intraCliqueManager ! message1
    inboundConnection.expectNoMessage()
    outboundConnection.expectMsg(BrokerHandler.Send(broadcastBlockMsg.headerMsg))

    val message2 = createBroadCastBlockMsg(DataOrigin.Local, ChainIndex.unsafe(1, 0))
    intraCliqueManager ! message2
    inboundConnection.expectMsg(BrokerHandler.Send(message2.blockMsg))
    outboundConnection.expectMsg(BrokerHandler.Send(message2.headerMsg))

    info("Broadcast Txs")

    intraCliqueManager ! IntraCliqueManager.BroadCastTx(AVector.empty)
    inboundConnection.expectNoMessage()
    outboundConnection.expectNoMessage()

    val tx        = block.transactions.head.toTemplate
    val invalidTx = block.transactions.head.toTemplate
    val txs = AVector(
      ChainIndex.unsafe(1, 0) -> AVector(tx),
      ChainIndex.unsafe(1, 2) -> AVector(tx)
    )
    val invalidTxs = AVector(
      ChainIndex.unsafe(1, 1) -> AVector(invalidTx)
    )
    val broadcastTxMsg =
      Message.serialize(TxsResponse(RequestId.unsafe(0), AVector(tx)))
    intraCliqueManager ! IntraCliqueManager.BroadCastTx(invalidTxs ++ txs)
    inboundConnection.expectMsg(BrokerHandler.Send(broadcastTxMsg))
    outboundConnection.expectMsg(BrokerHandler.Send(broadcastTxMsg))
  }

  it should "become ready immediately in single broker clique" in new Fixture {
    override val configValues: Map[String, Any] = Map(
      "alephium.broker.broker-num" -> 1
    )

    // create the lazy value
    intraCliqueManager ! broadcastBlockMsg
    cliqueManagerProbe.expectMsg(IntraCliqueManager.Ready)
  }

  trait Fixture extends FlowFixture with InfoFixture {
    val cliqueManagerProbe   = TestProbe()
    lazy val cliqueInfo      = genIntraCliqueInfo(brokerConfig.groupNumPerBroker).cliqueInfo
    lazy val (allHandler, _) = TestUtils.createAllHandlersProbe

    lazy val intraCliqueManager = system.actorOf(
      IntraCliqueManager.props(
        cliqueInfo,
        blockFlow,
        allHandler,
        ActorRefT(cliqueManagerProbe.ref),
        ActorRefT(TestProbe().ref)
      )
    )

    def createBroadCastBlockMsg(dataOrigin: DataOrigin, chainIndex: ChainIndex) = {
      val block = emptyBlock(blockFlow, chainIndex)
      IntraCliqueManager.BroadCastBlock(
        block,
        Message.serialize(NewBlock(block)),
        Message.serialize(NewHeader(block.header)),
        dataOrigin
      )
    }

    lazy val groupIndex        = brokerConfig.groupRange.head
    lazy val chainIndex        = ChainIndex.unsafe(groupIndex, groupIndex)
    lazy val block             = emptyBlock(blockFlow, chainIndex)
    lazy val broadcastBlockMsg = createBroadCastBlockMsg(DataOrigin.Local, chainIndex)
  }
}
