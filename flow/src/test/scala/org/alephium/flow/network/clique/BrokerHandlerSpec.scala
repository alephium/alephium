package org.alephium.flow.network.clique

import java.net.InetSocketAddress

import scala.util.Random

import akka.actor.{ActorRef, Props}
import akka.io.Tcp
import akka.testkit.{SocketUtil, TestProbe}
import akka.util.ByteString
import org.scalatest.EitherValues._

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.core.{AllHandlers, TestUtils}
import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.message._
import org.alephium.protocol.model.{BrokerInfo, CliqueId, CliqueInfo, ModelGen}
import org.alephium.serde.SerdeError
import org.alephium.util.AVector

class BrokerHandlerSpec extends AlephiumFlowActorSpec("BrokerHandlerSpec") {

  behavior of "BrokerHandler"

  trait BaseFixture {
    val remote = SocketUtil.temporaryServerAddress()
    val local  = SocketUtil.temporaryServerAddress()

    val localCliqueInfo = ModelGen.cliqueInfo.sample.get

    val message = Message(SendBlocks(AVector.empty))
    val data    = Message.serialize(message)

    val connection    = TestProbe("connection")
    val blockHandlers = TestUtils.createBlockHandlersProbe

    val payloadHandler = TestProbe("payload-probe")
  }

  it should "send hello to inbound connections" in new BaseFixture {
    val pingpongProbe = TestProbe()
    val builder = new BrokerHandler.Builder {
      override def createInboundBrokerHandler(
          selfCliqueInfo: CliqueInfo,
          remote: InetSocketAddress,
          connection: ActorRef,
          blockHandlers: AllHandlers)(implicit config: PlatformProfile): Props =
        Props(new InboundBrokerHandler(selfCliqueInfo, remote, connection, blockHandlers) {
          override def handleRelayPayload(payload: Payload): Unit = payloadHandler.ref ! payload

          override def startPingPong(): Unit = pingpongProbe.ref ! "start"
        })
    }
    val inboundBrokerHandler =
      system.actorOf(
        builder.createInboundBrokerHandler(localCliqueInfo, remote, connection.ref, blockHandlers))
    connection.expectMsgType[Tcp.Register]
    connection.expectMsgPF() {
      case write: Tcp.Write =>
        val message = Message.deserialize(write.data).right.value
        message.payload match {
          case hello: Hello =>
            hello.version is 0
            hello.cliqueId is localCliqueInfo.id
            hello.brokerInfo is config.brokerInfo
          case _ => assert(false)
        }
      case _ => assert(false)
    }

    val randomCliqueInfo = ModelGen.cliqueInfo.sample.get
    val randomId         = Random.nextInt(randomCliqueInfo.brokerNum)
    val randomAddress    = randomCliqueInfo.peers(randomId)
    val randomBroker     = BrokerInfo(randomId, randomCliqueInfo.groupNumPerBroker, randomAddress)
    val helloAck         = Message(HelloAck(randomCliqueInfo.id, randomBroker))
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
          blockHandlers: AllHandlers)(implicit config: PlatformProfile): Props = {
        Props(
          new OutboundBrokerHandler(selfCliqueInfo, remoteCliqueId, remoteBroker, blockHandlers) {
            override def handleRelayPayload(payload: Payload): Unit = payloadHandler.ref ! payload

            override def startPingPong(): Unit = pingpongProbe.ref ! "start"
          })
      }
    }
    val randomCliqueInfo = ModelGen.cliqueInfo.sample.get
    val randomBroker     = BrokerInfo.unsafe(0, config.groupNumPerBroker, remote) // TODO: improve
    val outboundBrokerHandler = system.actorOf(
      builder.createOutboundBrokerHandler(localCliqueInfo,
                                          randomCliqueInfo.id,
                                          randomBroker,
                                          blockHandlers))

    outboundBrokerHandler.tell(Tcp.Connected(remote, local), connection.ref)
    connection.expectMsgType[Tcp.Register]

    val hello = Message(Hello(randomCliqueInfo.id, randomBroker))
    outboundBrokerHandler ! Tcp.Received(Message.serialize(hello))
    connection.expectMsgPF() {
      case write: Tcp.Write =>
        val message = Message.deserialize(write.data).right.value
        message.payload match {
          case ack: HelloAck =>
            ack.cliqueId is localCliqueInfo.id
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
          connection: ActorRef,
          blockHandlers: AllHandlers)(implicit config: PlatformProfile): Props =
        Props(new InboundBrokerHandler(selfCliqueInfo, remote, connection, blockHandlers) {
          setPayloadHandler(payloadHandler.ref ! _)

          self ! BrokerHandler.TcpAck // confirm that hello is sent out
        })
    }
    val tcpHandler = system.actorOf(
      builder.createInboundBrokerHandler(localCliqueInfo, remote, connection.ref, blockHandlers))

    connection.expectMsgType[Tcp.Register]
    connection.expectMsgType[Tcp.Write]
  }

  it should "forward data to message handler" in new Fixture {
    tcpHandler ! Tcp.Received(data)
    payloadHandler.expectMsg(message.payload)
  }

  it should "stop when received corrupted data" in new Fixture {
    watch(tcpHandler)
    tcpHandler ! Tcp.Received(data.tail)
    expectTerminated(tcpHandler)
  }

  it should "handle message boundary correctly" in new Fixture {
    tcpHandler ! Tcp.Received(data.take(1))
    tcpHandler ! Tcp.Received(data.tail)
    payloadHandler.expectMsg(message.payload)
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

  behavior of "ping/ping protocol"

  trait PingPongFixture extends BaseFixture { obj =>

    val builder = new BrokerHandler.Builder {
      override def createInboundBrokerHandler(
          selfCliqueInfo: CliqueInfo,
          remote: InetSocketAddress,
          connection: ActorRef,
          blockHandlers: AllHandlers)(implicit config: PlatformProfile): Props =
        Props(new InboundBrokerHandler(selfCliqueInfo, remote, connection, blockHandlers) {
          setPayloadHandler(handleRelayPayload)

          self ! BrokerHandler.TcpAck
        })
    }
    val tcpHandler = system.actorOf(
      builder.createInboundBrokerHandler(localCliqueInfo, remote, connection.ref, blockHandlers))

    connection.expectMsgType[Tcp.Register]
    connection.expectMsgType[Tcp.Write]
  }

  it should "send ping after receiving SendPing" in new PingPongFixture {
    tcpHandler ! BrokerHandler.SendPing
    connection.expectMsgPF() {
      case Tcp.Write(data, _) =>
        val message = Message.deserialize(data).right.value
        message.payload is a[Ping]
    }
  }

  it should "reply pong to ping" in new PingPongFixture {
    val nonce    = Random.nextInt()
    val message1 = Message(Ping(nonce, System.currentTimeMillis()))
    val data1    = Message.serialize(message1)
    tcpHandler ! Tcp.Received(data1)
    connection.expectMsg(BrokerHandler.envelope(Message(Pong(nonce))))
  }

  it should "fail if receive a wrong ping" in new PingPongFixture {
    watch(tcpHandler)
    val nonce    = Random.nextInt()
    val message1 = Message(Pong(nonce))
    val data1    = Message.serialize(message1)
    tcpHandler ! Tcp.Received(data1)
    expectTerminated(tcpHandler)
  }
}
