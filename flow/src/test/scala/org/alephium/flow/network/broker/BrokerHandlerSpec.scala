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
import akka.testkit.{TestActorRef, TestProbe}

import org.alephium.flow.FlowFixture
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler._
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.flow.setting.NetworkSetting
import org.alephium.flow.validation.InvalidHeaderFlow
import org.alephium.protocol.{Generators, Signature, SignatureSchema}
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.message._
import org.alephium.protocol.model.{BlockHash, BrokerInfo, ChainIndex, CliqueId}
import org.alephium.util.{ActorRefT, AlephiumActorSpec, AVector, Duration, TimeStamp}

class BrokerHandlerSpec extends AlephiumActorSpec {
  it should "handshake with new connection" in new Fixture {
    receivedHandshakeMessage()
    brokerHandlerActor.pingPongTickOpt is a[Some[_]]
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

  it should "stop when handshake message contains invalid client id" in new Fixture {
    override val configValues = Map(("alephium.network.network-id", 1))

    networkConfig.networkId.id is 1.toByte
    networkConfig.getHardFork(TimeStamp.now()).isRhoneEnabled() is true

    watch(brokerHandler)
    brokerHandler ! BrokerHandler.Received(
      Hello.unsafe(
        "scala-alephium/v2.13.1/Linux",
        TimeStamp.now(),
        brokerInfo.interBrokerInfo,
        Signature.zero
      )
    )
    expectTerminated(brokerHandler)
    listener.expectMsg(MisbehaviorManager.InvalidClientVersion(remoteAddress))
  }

  it should "publish misbehavior if receive invalid ping" in new Fixture {
    receivedHandshakeMessage()
    val requestId = RequestId.random()
    brokerHandler ! BrokerHandler.Received(Ping(requestId, TimeStamp.now()))
    val message = Message.serialize(Pong(requestId))
    connectionHandler.expectMsg(ConnectionHandler.Send(message))

    watch(brokerHandler)
    brokerHandler ! BrokerHandler.Received(Ping(RequestId.unsafe(0), TimeStamp.now()))
    listener.expectMsg(MisbehaviorManager.InvalidPingPongCritical(remoteAddress))
    expectTerminated(brokerHandler)
  }

  it should "publish misbehavior if receive invalid pong" in new Fixture {
    override val pingFrequency: Duration = Duration.ofMillisUnsafe(500)
    receivedHandshakeMessage()
    val ping = connectionHandler.expectMsgPF() { case ConnectionHandler.Send(message) =>
      Message.deserialize(message).rightValue.payload.asInstanceOf[Ping]
    }
    brokerHandlerActor.pingRequestId is ping.id
    brokerHandler ! BrokerHandler.Received(Pong(ping.id))
    brokerHandlerActor.pingRequestId is RequestId.unsafe(0)

    brokerHandler ! BrokerHandler.Received(Pong(RequestId.random()))
    listener.expectMsg(MisbehaviorManager.InvalidPingPong(remoteAddress))
  }

  it should "publish misbehavior if ping timeout" in new Fixture {
    override val pingFrequency: Duration = Duration.ofMillisUnsafe(500)
    receivedHandshakeMessage()
    eventually {
      listener.expectMsg(MisbehaviorManager.RequestTimeout(remoteAddress))
    }
  }

  it should "notify synchronizer when block added" in new Fixture {
    receivedHandshakeMessage()
    val hash = BlockHash.generate
    brokerHandler ! BlockChainHandler.BlockAdded(hash)
    blockFlowSynchronizer.expectMsg(BlockFlowSynchronizer.BlockFinalized(hash))
  }

  it should "publish misbehavior if block is invalid" in new Fixture {
    receivedHandshakeMessage()
    val hash = BlockHash.generate
    brokerHandler ! BlockChainHandler.InvalidBlock(hash, InvalidHeaderFlow)
    blockFlowSynchronizer.expectMsg(BlockFlowSynchronizer.BlockFinalized(hash))
    listener.expectMsg(MisbehaviorManager.InvalidFlowData(remoteAddress))
  }

  it should "publish misbehavior if block header is invalid" in new Fixture {
    receivedHandshakeMessage()
    val hash = BlockHash.generate
    brokerHandler ! HeaderChainHandler.InvalidHeader(hash)
    listener.expectMsg(MisbehaviorManager.InvalidFlowData(remoteAddress))
  }

  it should "send blocks request" in new Fixture {
    receivedHandshakeMessage()
    val hashes = AVector(BlockHash.generate)
    brokerHandler ! BrokerHandler.DownloadBlocks(hashes)
    connectionHandler.expectMsgPF() { case ConnectionHandler.Send(message) =>
      Message.deserialize(message).rightValue.payload.asInstanceOf[BlocksRequest].locators is hashes
    }
  }

  it should "send data to peer" in new Fixture {
    receivedHandshakeMessage()
    val data = BlockHash.generate.bytes
    brokerHandler ! BrokerHandler.Send(data)
    connectionHandler.expectMsg(ConnectionHandler.Send(data))
  }

  it should "publish misbehavior when received invalid block" in new Fixture {
    override val configValues = Map(
      ("alephium.consensus.num-zeros-at-least-in-hash", 1)
    )

    receivedHandshakeMessage()
    val chainIndex = ChainIndex.unsafe(0, 0)
    val block      = invalidNonceBlock(blockFlow, chainIndex)
    brokerHandler ! BrokerHandler.Received(NewBlock(block))
    listener.expectMsg(MisbehaviorManager.InvalidPoW(remoteAddress))
  }

  it should "handle headers request" in new Fixture {
    receivedHandshakeMessage()
    val chainIndex = ChainIndex.unsafe(0, 0)
    val block      = emptyBlock(blockFlow, chainIndex)
    addAndCheck(blockFlow, block)
    val request = HeadersRequest(AVector(block.hash))
    brokerHandler ! BrokerHandler.Received(request)
    val response = HeadersResponse(request.id, AVector(block.header))
    connectionHandler.expectMsg(ConnectionHandler.Send(Message.serialize(response)))
  }

  it should "handle blocks response" in new Fixture {
    receivedHandshakeMessage()
    val chainIndex = ChainIndex.unsafe(0, 0)
    val block      = emptyBlock(blockFlow, chainIndex)
    addAndCheck(blockFlow, block)
    val response = BlocksResponse.fromBlocks(RequestId.random(), AVector(block))
    brokerHandler ! BrokerHandler.Received(response)
    eventually {
      allHandlerProbes.dependencyHandler.expectMsg(
        DependencyHandler.AddFlowData(AVector(block), DataOrigin.Local)
      )
    }
  }

  trait Fixture extends FlowFixture with Generators {
    val connectionHandler     = TestProbe()
    val blockFlowSynchronizer = TestProbe()
    val listener              = TestProbe()
    val remoteAddress         = socketAddressGen.sample.get
    val (priKey, pubKey)      = SignatureSchema.secureGeneratePriPub()
    val pingFrequency         = Duration.ofSecondsUnsafe(10)

    lazy val (allHandlers, allHandlerProbes) = TestUtils.createAllHandlersProbe
    lazy val brokerHandler = {
      val handler = TestActorRef[TestBrokerHandler](
        TestBrokerHandler.props(
          pingFrequency,
          remoteAddress,
          connectionHandler.ref,
          blockFlowSynchronizer.ref,
          blockFlow,
          allHandlers
        )
      )
      val message = Message.serialize(handler.underlyingActor.handShakeMessage)
      connectionHandler.expectMsg(ConnectionHandler.Send(message))
      handler.underlyingActor.pingPongTickOpt is None
      handler
    }
    lazy val brokerHandlerActor = brokerHandler.underlyingActor
    lazy val brokerInfo =
      BrokerInfo.unsafe(CliqueId(pubKey), 0, 1, socketAddressGen.sample.get)
    system.eventStream.subscribe(listener.ref, classOf[MisbehaviorManager.Misbehavior])

    def receivedHandshakeMessage() = {
      val hello = Hello.unsafe(brokerInfo.interBrokerInfo, priKey)
      brokerHandler ! BrokerHandler.Received(hello)
    }
  }
}

object TestBrokerHandler {
  def props(
      pingFrequency: Duration,
      remoteAddress: InetSocketAddress,
      brokerConnectionHandler: ActorRefT[ConnectionHandler.Command],
      blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command],
      blockflow: BlockFlow,
      allHandlers: AllHandlers
  )(implicit brokerConfig: BrokerConfig, networkSetting: NetworkSetting): Props = {
    Props(
      new TestBrokerHandler(
        pingFrequency,
        remoteAddress,
        brokerConnectionHandler,
        blockFlowSynchronizer,
        blockflow,
        allHandlers
      )
    )
  }
}

class TestBrokerHandler(
    val pingFrequency: Duration,
    val remoteAddress: InetSocketAddress,
    val brokerConnectionHandler: ActorRefT[ConnectionHandler.Command],
    val blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command],
    val blockflow: BlockFlow,
    val allHandlers: AllHandlers
)(implicit val brokerConfig: BrokerConfig, val networkSetting: NetworkSetting)
    extends BrokerHandler {
  val connectionType: ConnectionType = OutboundConnection

  val (priKey, pubKey) = SignatureSchema.secureGeneratePriPub()

  override def handShakeDuration: Duration = Duration.ofSecondsUnsafe(2)

  val brokerInfo = BrokerInfo.unsafe(CliqueId(pubKey), 0, 1, new InetSocketAddress("127.0.0.1", 0))

  override val handShakeMessage: Payload = Hello.unsafe(brokerInfo.interBrokerInfo, priKey)

  override def exchanging: Receive = exchangingCommon orElse flowEvents

  override def dataOrigin: DataOrigin = DataOrigin.Local

  def handleHandshakeInfo(_remoteBrokerInfo: BrokerInfo, clientInfo: String): Unit = {
    remoteBrokerInfo = _remoteBrokerInfo
  }
}
