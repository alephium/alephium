package org.alephium.flow.network.clique

import java.net.InetSocketAddress

import scala.annotation.tailrec
import scala.collection.mutable

import akka.actor.Props
import akka.io.Tcp
import akka.testkit.{TestActorRef, TestProbe}
import akka.util.ByteString
import org.scalatest.EitherValues._

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.handler.{AllHandlers, FlowHandler, TestUtils, TxHandler}
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.message._
import org.alephium.protocol.model._
import org.alephium.serde.SerdeError
import org.alephium.util.{ActorRefT, AVector, Random}

class BrokerHandlerSpec
    extends AlephiumFlowActorSpec("BrokerHandlerSpec")
    with NoIndexModelGeneratorsLike { Spec =>
  def genBroker(): (InetSocketAddress, CliqueInfo, BrokerInfo) = {
    val cliqueInfo = cliqueInfoGen.sample.get
    val id         = Random.source.nextInt(cliqueInfo.brokerNum)
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

  behavior of "BrokerHandler"

  it should "send hello to inbound connections" in new BaseFixture {
    val pingpongProbe = TestProbe()
    val inboundBrokerHandler =
      system.actorOf(Props(
        new InboundBrokerHandler(selfCliqueInfo, remote, connectionT, allHandlers, cliqueManagerT) {
          override def handleRelayPayload(payload: Payload): Unit = payloadHandler.ref ! payload

          override def startPingPong(): Unit = pingpongProbe.ref ! "start"

          isSyncing is false
        }))

    connection.expectMsgType[Tcp.Register]
    connection.expectMsgPF() {
      case write: Tcp.Write =>
        val message = Message.deserialize(write.data).toOption.get
        message.payload match {
          case hello: Hello =>
            hello.version is 0
            hello.cliqueId is selfCliqueInfo.id
            hello.brokerInfo is selfCliqueInfo.selfBrokerInfo
          case _ => assume(false)
        }
      case _ => assume(false)
    }

    val helloAck = HelloAck.unsafe(remoteCliqueInfo.id, remoteBrokerInfo)
    inboundBrokerHandler ! Tcp.Received(Message.serialize(helloAck))
    pingpongProbe.expectMsg("start")
  }

  it should "response HelloAck to Hello" in new BaseFixture {
    val pingpongProbe = TestProbe()
    val outboundBrokerHandler = system.actorOf(
      Props(
        new OutboundBrokerHandler(selfCliqueInfo,
                                  remoteCliqueInfo.id,
                                  remoteBrokerInfo,
                                  allHandlers,
                                  cliqueManagerT) {
          override def handleRelayPayload(payload: Payload): Unit = payloadHandler.ref ! payload

          override def startPingPong(): Unit = pingpongProbe.ref ! "start"

          isSyncing is false
        }))

    outboundBrokerHandler.tell(Tcp.Connected(remote, local), connection.ref)
    connection.expectMsgType[Tcp.Register]

    val hello = Hello.unsafe(remoteCliqueInfo.id, remoteBrokerInfo)
    outboundBrokerHandler ! Tcp.Received(Message.serialize(hello))
    connection.expectMsgPF() {
      case write: Tcp.Write =>
        val message = Message.deserialize(write.data).toOption.get
        message.payload match {
          case ack: HelloAck =>
            ack.cliqueId is selfCliqueInfo.id
            ack.brokerInfo is selfCliqueInfo.selfBrokerInfo
          case _ => assume(false)
        }
      case _ => assume(false)
    }
    pingpongProbe.expectMsg("start")
  }

  trait Fixture extends BaseFixture { obj =>
    val tcpHandler = system.actorOf(
      Props(
        new InboundBrokerHandler(selfCliqueInfo, remote, connectionT, allHandlers, cliqueManagerT) {
          setPayloadHandler(payloadHandler.ref ! _)

          self ! BrokerHandler.TcpAck // confirm that hello is sent out
        })
    )

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
    val result = BrokerHandler.deserialize(bytes).toOption.get
    result._1 is AVector(message1, message2)
    result._2 is ByteString.empty
    for (n <- bytes.indices) {
      val input  = bytes.take(n)
      val output = BrokerHandler.deserialize(input).toOption.get
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
    val tcpHandler = system.actorOf(
      Props(
        new InboundBrokerHandler(selfCliqueInfo, remote, connectionT, allHandlers, cliqueManagerT) {
          override val allHandlers: AllHandlers = obj.allHandlers

          override def unhandled(message: Any): Unit = {
            throw new RuntimeException(s"Test Failed: $message")
          }

          startRelay()

          self ! BrokerHandler.TcpAck
        })
    )

    connection.expectMsgType[Tcp.Register]
    connection.expectMsgType[Tcp.Write]
  }

  behavior of "ping/ping protocol"

  it should "send ping after receiving SendPing" in new RelayFixture {
    tcpHandler ! BrokerHandler.SendPing
    connection.expectMsgPF() {
      case Tcp.Write(data, _) =>
        val message = Message.deserialize(data).toOption.get
        message.payload is a[Ping]
    }
  }

  it should "reply pong to ping" in new RelayFixture {
    val nonce    = Random.source.nextInt()
    val message1 = Ping(nonce, System.currentTimeMillis())
    val data1    = Message.serialize(message1)
    tcpHandler ! Tcp.Received(data1)
    connection.expectMsg(BrokerHandler.envelope(Message(Pong(nonce))))
  }

  it should "fail if receive a wrong ping" in new RelayFixture {
    watch(tcpHandler)
    val nonce    = Random.source.nextInt()
    val message1 = Pong(nonce)
    val data1    = Message.serialize(message1)
    tcpHandler ! Tcp.Received(data1)
    expectTerminated(tcpHandler)
  }

  behavior of "Relay protocol"

  it should "send tx to txHandler" in new RelayFixture {
    val tx     = transactionGen().sample.get
    val sendTx = SendTxs(AVector(tx))
    tcpHandler ! Tcp.Received(Message.serialize(sendTx))
    allProbes.txHandler.expectMsgType[TxHandler.AddTx]

    tcpHandler ! TxHandler.AddSucceeded(tx.hash)
    tcpHandler ! TxHandler.AddFailed(tx.hash)
  }

  behavior of "Sync protocol"

  trait SyncFixture extends BaseFixture { Base =>
    @tailrec
    final def genRemoteClique(valid: Boolean): (CliqueInfo, BrokerInfo) = {
      val (_, cliqueInfo, brokerInfo) = genBroker()
      if (brokerInfo.intersect(brokerConfig) equals valid) (cliqueInfo, brokerInfo)
      else genRemoteClique(valid)
    }

    val syncHandlerRef = TestActorRef(new BrokerHandler {
      override def brokerConfig: BrokerConfig                      = Spec.brokerConfig
      override def networkSetting: NetworkSetting                  = Spec.networkSetting
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
    watch(syncHandlerRef)
    val syncHandler = syncHandlerRef.underlyingActor

    def testSync(remoteCliqueInfo: CliqueInfo,
                 remoteBrokerInfo: BrokerInfo,
                 valid: Boolean): Object = {
      syncHandler.isSyncing is false
      syncHandler.uponHandshaked(remoteCliqueInfo.id, remoteBrokerInfo)

      if (valid) {
        syncHandler.isSyncing is true

        val block      = blockGenOf(brokerConfig).sample.get
        val blocksMsg0 = Message.serialize(SyncResponse(AVector(block), AVector.empty))
        syncHandlerRef ! Tcp.Received(blocksMsg0)
        syncHandler.isSyncing is true
        val blocksMsg1 = Message.serialize(SyncResponse(AVector.empty, AVector.empty))
        syncHandlerRef ! Tcp.Received(blocksMsg1)
        syncHandlerRef ! FlowHandler.SyncData(AVector.empty, AVector.empty)
        syncHandler.isSyncing is false
      } else {
        expectTerminated(syncHandlerRef)
      }
    }
  }

  it should "not sync invalid intra-clique node" in new SyncFixture {
    testSync(selfCliqueInfo, selfBrokerInfo, false)
  }

  it should "sync intra-clique node" in new SyncFixture {
    val remoteCliqueInfoNew: CliqueInfo =
      CliqueInfo.unsafe(this.selfCliqueInfo.id, AVector.empty, brokerConfig.groupNumPerBroker)
    val remoteBrokerInfoNew: BrokerInfo =
      BrokerInfo.unsafe((brokerConfig.brokerId + 1) % brokerConfig.brokerNum,
                        brokerConfig.groupNumPerBroker,
                        remote)
    testSync(remoteCliqueInfoNew, remoteBrokerInfoNew, true)
  }

  it should "not sync inter-clique node" in new SyncFixture {

    val (invalidRemoteCliqueInfo, invalidRemoteBrokerInfo) = genRemoteClique(false)
    invalidRemoteCliqueInfo.id isnot selfCliqueInfo.id
    testSync(invalidRemoteCliqueInfo, invalidRemoteBrokerInfo, false)
  }

  it should "sync inter-clique node" in new SyncFixture {
    val (validRemoteCliqueInfo, validRemoteBrokerInfo) = genRemoteClique(true)
    validRemoteCliqueInfo.id isnot selfCliqueInfo.id
    testSync(validRemoteCliqueInfo, validRemoteBrokerInfo, true)
  }
}
