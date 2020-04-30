package org.alephium.flow.network.clique

import java.net.InetSocketAddress

import scala.collection.mutable
import scala.util.Random

import akka.actor.Props
import akka.io.Tcp
import akka.testkit.{TestActorRef, TestProbe}
import akka.util.ByteString
import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.core.{AllHandlers, FlowHandler, TestUtils, TxHandler}
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.message._
import org.alephium.protocol.model._
import org.alephium.serde.SerdeError
import org.alephium.util.{ActorRefT, AVector}

class BrokerHandlerSpec extends AlephiumFlowActorSpec("BrokerHandlerSpec") { Spec =>
  behavior of "BrokerHandler"

  def genBroker(): (InetSocketAddress, CliqueInfo, BrokerInfo) = {
    val cliqueInfo = ModelGen.cliqueInfo.sample.get
    val id         = Random.nextInt(cliqueInfo.brokerNum)
    val address    = cliqueInfo.peers(id)
    val brokerInfo = BrokerInfo.unsafe(id, cliqueInfo.groupNumPerBroker, address)
    (address, cliqueInfo, brokerInfo)
  }

  trait BaseFixture {
    val (remote, remoteCliqueInfo, remoteBrokerInfo) = genBroker()
    val (local, selfCliqueInfo, selfBrokerInfo)      = genBroker()

    val message = SendBlocks(AVector.empty)
    val data    = Message.serialize(message)

    val connection               = TestProbe("connection")
    val connectionT              = ActorRefT[Tcp.Command](connection.ref)
    val (allHandlers, allProbes) = TestUtils.createBlockHandlersProbe
    val payloadHandler           = TestProbe("payload-probe")
    val cliqueManager            = TestProbe("clique-manager")
    val cliqueManagerT           = ActorRefT[CliqueManager.Command](cliqueManager.ref)
  }

  it should "send hello to inbound connections" in new BaseFixture {
    val pingpongProbe = TestProbe()
    val builder = new BrokerHandler.Builder {
      override def createInboundBrokerHandler(
          selfCliqueInfo: CliqueInfo,
          remote: InetSocketAddress,
          connection: ActorRefT[Tcp.Command],
          blockHandlers: AllHandlers,
          cliqueManager: ActorRefT[CliqueManager.Command]
      )(implicit config: PlatformConfig): Props =
        Props(new InboundBrokerHandler(selfCliqueInfo,
                                       remote,
                                       connection,
                                       blockHandlers,
                                       cliqueManager) {
          override def handleRelayPayload(payload: Payload): Unit = payloadHandler.ref ! payload

          override def startPingPong(): Unit = pingpongProbe.ref ! "start"

          isSyncing is false
        })
    }
    val inboundBrokerHandler =
      system.actorOf(
        builder.createInboundBrokerHandler(selfCliqueInfo,
                                           remote,
                                           connectionT,
                                           allHandlers,
                                           cliqueManagerT))
    connection.expectMsgType[Tcp.Register]
    connection.expectMsgPF() {
      case write: Tcp.Write =>
        val message = Message.deserialize(write.data).right.value
        message.payload match {
          case hello: Hello =>
            hello.version is 0
            hello.cliqueId is selfCliqueInfo.id
            hello.brokerInfo is config.brokerInfo
          case _ => assert(false)
        }
      case _ => assert(false)
    }

    val helloAck = HelloAck.unsafe(remoteCliqueInfo.id, remoteBrokerInfo)
    inboundBrokerHandler ! Tcp.Received(Message.serialize(helloAck))
    pingpongProbe.expectMsg("start")
  }

  it should "response HelloAck to Hello" in new BaseFixture {
    val pingpongProbe = TestProbe()
    val builder = new BrokerHandler.Builder {
      override def createOutboundBrokerHandler(
          selfCliqueInfo: CliqueInfo,
          remoteCliqueId: CliqueId,
          remoteBroker: BrokerInfo,
          blockHandlers: AllHandlers,
          cliqueManager: ActorRefT[CliqueManager.Command]
      )(implicit config: PlatformConfig): Props = {
        Props(
          new OutboundBrokerHandler(selfCliqueInfo,
                                    remoteCliqueId,
                                    remoteBroker,
                                    blockHandlers,
                                    cliqueManager) {
            override def handleRelayPayload(payload: Payload): Unit = payloadHandler.ref ! payload

            override def startPingPong(): Unit = pingpongProbe.ref ! "start"

            isSyncing is false
          })
      }
    }
    val outboundBrokerHandler = system.actorOf(
      builder.createOutboundBrokerHandler(selfCliqueInfo,
                                          remoteCliqueInfo.id,
                                          remoteBrokerInfo,
                                          allHandlers,
                                          cliqueManagerT))

    outboundBrokerHandler.tell(Tcp.Connected(remote, local), connection.ref)
    connection.expectMsgType[Tcp.Register]

    val hello = Hello.unsafe(remoteCliqueInfo.id, remoteBrokerInfo)
    outboundBrokerHandler ! Tcp.Received(Message.serialize(hello))
    connection.expectMsgPF() {
      case write: Tcp.Write =>
        val message = Message.deserialize(write.data).right.value
        message.payload match {
          case ack: HelloAck =>
            ack.cliqueId is selfCliqueInfo.id
            ack.brokerInfo is config.brokerInfo
          case _ => assert(false)
        }
      case _ => assert(false)
    }
    pingpongProbe.expectMsg("start")
  }

  trait Fixture extends BaseFixture { obj =>
    val builder = new BrokerHandler.Builder {
      override def createInboundBrokerHandler(
          selfCliqueInfo: CliqueInfo,
          remote: InetSocketAddress,
          connection: ActorRefT[Tcp.Command],
          blockHandlers: AllHandlers,
          cliqueManager: ActorRefT[CliqueManager.Command])(implicit config: PlatformConfig): Props =
        Props(new InboundBrokerHandler(selfCliqueInfo,
                                       remote,
                                       connection,
                                       blockHandlers,
                                       cliqueManager) {
          setPayloadHandler(payloadHandler.ref ! _)

          self ! BrokerHandler.TcpAck // confirm that hello is sent out
        })
    }
    val tcpHandler = system.actorOf(builder
      .createInboundBrokerHandler(selfCliqueInfo, remote, connectionT, allHandlers, cliqueManagerT))

    connection.expectMsgType[Tcp.Register]
    connection.expectMsgType[Tcp.Write]
  }

  it should "forward data to message handler" in new Fixture {
    tcpHandler ! Tcp.Received(data)
    payloadHandler.expectMsg(message)
  }

  it should "stop when received corrupted data" in new Fixture {
    watch(tcpHandler)
    tcpHandler ! Tcp.Received(data.tail)
    expectTerminated(tcpHandler)
  }

  it should "handle message boundary correctly" in new Fixture {
    tcpHandler ! Tcp.Received(data.take(1))
    tcpHandler ! Tcp.Received(data.tail ++ data.take(1))
    tcpHandler ! Tcp.Received(data.tail)
    payloadHandler.expectMsg(message)
    payloadHandler.expectMsg(message)
  }

  it should "stop when tcp connection closed" in new Fixture {
    watch(tcpHandler)
    tcpHandler ! Tcp.Closed
    expectTerminated(tcpHandler)
  }

  it should "send data out when new message generated" in new Fixture {
    val bytes = Message.serialize(message)
    tcpHandler ! bytes
    connection.expectMsg(Tcp.Write(bytes, BrokerHandler.TcpAck))
  }

  behavior of "Deserialization"

  trait SerdeFixture {
    val message1 = Message(Ping(1, System.currentTimeMillis()))
    val message2 = Message(Pong(2))
    val bytes1   = Message.serialize(message1)
    val bytes2   = Message.serialize(message2)
    val bytes    = bytes1 ++ bytes2
  }

  it should "deserialize two messages correctly" in new SerdeFixture {
    val result = BrokerHandler.deserialize(bytes).right.value
    result._1 is AVector(message1, message2)
    result._2 is ByteString.empty
    for (n <- bytes.indices) {
      val input  = bytes.take(n)
      val output = BrokerHandler.deserialize(input).right.value
      if (n < bytes1.length) {
        output._1 is AVector.empty[Message]
        output._2 is input
      } else {
        output._1 is AVector(message1)
        output._2 is input.drop(bytes1.length)
      }
    }
  }

  it should "fail when data is corrupted" in new SerdeFixture {
    val exception1 = BrokerHandler.deserialize(bytes.tail).left.value
    exception1 is a[SerdeError.WrongFormat]
    val exception2 = BrokerHandler.deserialize(bytes1 ++ bytes2.tail).left.value
    exception2 is a[SerdeError.WrongFormat]
  }

  trait RelayFixture extends BaseFixture { obj =>
    val builder = new BrokerHandler.Builder {
      override def createInboundBrokerHandler(
          selfCliqueInfo: CliqueInfo,
          remote: InetSocketAddress,
          connection: ActorRefT[Tcp.Command],
          allHandlers: AllHandlers,
          cliqueManager: ActorRefT[CliqueManager.Command])(implicit config: PlatformConfig): Props =
        Props(
          new InboundBrokerHandler(selfCliqueInfo, remote, connection, allHandlers, cliqueManager) {
            override val allHandlers: AllHandlers = obj.allHandlers

            override def unhandled(message: Any): Unit = {
              throw new RuntimeException(s"Test Failed: $message")
            }

            startRelay()

            self ! BrokerHandler.TcpAck
          })
    }
    val tcpHandler = system.actorOf(builder
      .createInboundBrokerHandler(selfCliqueInfo, remote, connectionT, allHandlers, cliqueManagerT))

    connection.expectMsgType[Tcp.Register]
    connection.expectMsgType[Tcp.Write]
  }

  behavior of "ping/ping protocol"

  it should "send ping after receiving SendPing" in new RelayFixture {
    tcpHandler ! BrokerHandler.SendPing
    connection.expectMsgPF() {
      case Tcp.Write(data, _) =>
        val message = Message.deserialize(data).right.value
        message.payload is a[Ping]
    }
  }

  it should "reply pong to ping" in new RelayFixture {
    val nonce    = Random.nextInt()
    val message1 = Ping(nonce, System.currentTimeMillis())
    val data1    = Message.serialize(message1)
    tcpHandler ! Tcp.Received(data1)
    connection.expectMsg(BrokerHandler.envelope(Message(Pong(nonce))))
  }

  it should "fail if receive a wrong ping" in new RelayFixture {
    watch(tcpHandler)
    val nonce    = Random.nextInt()
    val message1 = Pong(nonce)
    val data1    = Message.serialize(message1)
    tcpHandler ! Tcp.Received(data1)
    expectTerminated(tcpHandler)
  }

  behavior of "Relay protocol"

  it should "send tx to txHandler" in new RelayFixture {
    val tx     = ModelGen.transactionGen.sample.get
    val sendTx = SendTxs(AVector(tx))
    tcpHandler ! Tcp.Received(Message.serialize(sendTx))
    allProbes.txHandler.expectMsgType[TxHandler.AddTx]

    tcpHandler ! TxHandler.AddSucceeded
    tcpHandler ! TxHandler.AddFailed
  }

  behavior of "Sync protocol"

  trait SyncFixture extends BaseFixture { Base =>
    val syncHandlerRef = TestActorRef(new BrokerHandler {
      override def config: PlatformConfig                          = Spec.config
      override def remote: InetSocketAddress                       = Base.remote
      var remoteCliqueId: CliqueId                                 = _
      var remoteBrokerInfo: BrokerInfo                             = _
      override def selfCliqueInfo: CliqueInfo                      = Base.selfCliqueInfo
      override def connection: ActorRefT[Tcp.Command]              = Base.connectionT
      override def cliqueManager: ActorRefT[CliqueManager.Command] = Base.cliqueManagerT
      override def allHandlers: AllHandlers                        = Base.allHandlers

      override def receive: Receive = { case _ => () }

      override def handleBrokerInfo(_remoteCliqueId: CliqueId,
                                    _remoteBrokerInfo: BrokerInfo): Unit = {
        remoteCliqueId   = _remoteCliqueId
        remoteBrokerInfo = _remoteBrokerInfo
      }

      override def handleSendBlocks(blocks: AVector[Block],
                                    notifyListOpt: Option[mutable.HashSet[ChainIndex]]): Unit = ()
    })
    val syncHandler = syncHandlerRef.underlyingActor

    def testSync(remoteCliqueInfo: CliqueInfo, remoteBrokerInfo: BrokerInfo): Assertion = {
      syncHandler.isSyncing is false
      syncHandler.uponHandshaked(remoteCliqueInfo.id, remoteBrokerInfo)
      syncHandler.isSyncing is true

      val block      = ModelGen.blockGenFor(config.brokerInfo).sample.get
      val blocksMsg0 = Message.serialize(SyncResponse(AVector(block), AVector.empty))
      syncHandlerRef ! Tcp.Received(blocksMsg0)
      syncHandler.isSyncing is true
      val blocksMsg1 = Message.serialize(SyncResponse(AVector.empty, AVector.empty))
      syncHandlerRef ! Tcp.Received(blocksMsg1)
      syncHandlerRef ! FlowHandler.SyncData(AVector.empty, AVector.empty)
      syncHandler.isSyncing is false
    }
  }

  it should "sync intra-clique node" in new SyncFixture {
    val remoteCliqueInfoNew: CliqueInfo =
      CliqueInfo.unsafe(this.selfCliqueInfo.id, AVector.empty, config.groupNumPerBroker)
    val remoteBrokerInfoNew: BrokerInfo =
      BrokerInfo.unsafe((config.brokerInfo.id + 1) % config.brokerNum,
                        config.groupNumPerBroker,
                        remote)
    testSync(remoteCliqueInfoNew, remoteBrokerInfoNew)
  }

  it should "sync inter-clique node" in new SyncFixture {
    remoteCliqueInfo.id isnot selfCliqueInfo.id
    testSync(remoteCliqueInfo, config.brokerInfo)
  }
}
