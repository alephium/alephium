package org.alephium.flow.network

import java.net.InetSocketAddress
import java.time.Instant

import akka.actor.Props
import akka.io.Tcp
import akka.testkit.{SocketUtil, TestProbe}
import akka.util.ByteString
import org.alephium.flow.PlatformConfig
import org.alephium.flow.storage.{AllHandlers, HandlerUtils}
import org.alephium.protocol.message._
import org.alephium.protocol.model.PeerId
import org.alephium.serde.WrongFormatException
import org.alephium.util.{AVector, AlephiumActorSpec}
import org.scalatest.TryValues._

import scala.util.Random

class TcpHandlerSpec extends AlephiumActorSpec("TcpHandlerSpec") {

  behavior of "TcpHandler"

  trait BaseFixture extends PlatformConfig.Default {
    val remote = SocketUtil.temporaryServerAddress()
    val local  = SocketUtil.temporaryServerAddress()

    val message = Message(SendBlocks(AVector.empty))
    val data    = Message.serializer.serialize(message)

    val connection    = TestProbe("connection")
    val blockHandlers = HandlerUtils.createBlockHandlersProbe

    val payloadHandler = TestProbe("payload-probe")
  }

  it should "send Hello to incoming connection" in new BaseFixture {
    val pingpongProbe = TestProbe()
    val builder = new TcpHandler.Builder {
      override def createTcpHandler(remote: InetSocketAddress, blockHandlers: AllHandlers)(
          implicit config: PlatformConfig): Props = {
        Props(new TcpHandler(remote, blockHandlers) {
          override def handlePayload(payload: Payload): Unit = payloadHandler.ref ! payload

          override def startPingPong(): Unit = pingpongProbe.ref ! "start"
        })
      }
    }
    val tcpHandler = system.actorOf(builder.createTcpHandler(remote, blockHandlers))

    tcpHandler ! TcpHandler.Set(connection.ref)
    connection.expectMsgType[Tcp.Register]
    connection.expectMsgPF() {
      case write: Tcp.Write =>
        val message = Message.deserializer.deserialize(write.data).get
        message.payload match {
          case Hello(0, _, peerId) => peerId is config.peerId
          case _                   => assert(false)
        }
      case _ => assert(false)
    }

    val randomId = PeerId.generate
    val helloAck = Message(HelloAck(randomId))
    tcpHandler ! Tcp.Received(Message.serializer.serialize(helloAck))
    pingpongProbe.expectMsg("start")
  }

  it should "response HelloAck to Hello" in new BaseFixture {
    val pingpongProbe = TestProbe()
    val builder = new TcpHandler.Builder {
      override def createTcpHandler(remote: InetSocketAddress, blockHandlers: AllHandlers)(
          implicit config: PlatformConfig): Props = {
        Props(new TcpHandler(remote, blockHandlers) {
          override def receive: Receive = connecting(Instant.now)

          override def handlePayload(payload: Payload): Unit = payloadHandler.ref ! payload

          override def startPingPong(): Unit = pingpongProbe.ref ! "start"
        })
      }
    }
    val tcpHandler = system.actorOf(builder.createTcpHandler(remote, blockHandlers))

    tcpHandler.tell(Tcp.Connected(remote, local), connection.ref)
    connection.expectMsgType[Tcp.Register]

    val randomId = PeerId.generate
    val hello    = Message(Hello(randomId))
    tcpHandler ! Tcp.Received(Message.serializer.serialize(hello))
    connection.expectMsgPF() {
      case write: Tcp.Write =>
        val message = Message.deserializer.deserialize(write.data).get
        message.payload match {
          case HelloAck(0, _, peerId) => peerId is config.peerId
          case _                      => assert(false)
        }
      case _ => assert(false)
    }
    pingpongProbe.expectMsg("start")
  }

  trait Fixture extends BaseFixture { obj =>
    val builder = new TcpHandler.Builder {
      override def createTcpHandler(remote: InetSocketAddress, blockHandlers: AllHandlers)(
          implicit config: PlatformConfig): Props = {
        Props(new TcpHandler(remote, blockHandlers) {
          connection = obj.connection.ref

          override def receive: Receive = handleWith(ByteString.empty, handlePayload)

          override def handlePayload(payload: Payload): Unit = payloadHandler.ref ! payload
        })
      }
    }
    val tcpHandler = system.actorOf(builder.createTcpHandler(remote, blockHandlers))
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
    tcpHandler ! message
    connection.expectMsg(TcpHandler.envelope(message))
  }

  behavior of "Deserialization"

  trait SerdeFixture {
    val message1 = Message(Ping(1, System.currentTimeMillis()))
    val message2 = Message(Pong(2))
    val bytes1   = Message.serializer.serialize(message1)
    val bytes2   = Message.serializer.serialize(message2)
    val bytes    = bytes1 ++ bytes2
  }

  it should "deserialize two messages correctly" in new SerdeFixture {
    val result = TcpHandler.deserialize(bytes).success.value
    result._1 is AVector(message1, message2)
    result._2 is ByteString.empty
    for (n <- bytes.indices) {
      val input  = bytes.take(n)
      val output = TcpHandler.deserialize(input).success.value
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
    val exception1 = TcpHandler.deserialize(bytes.tail).failure.exception
    exception1 is a[WrongFormatException]
    val exception2 = TcpHandler.deserialize(bytes1 ++ bytes2.tail).failure.exception
    exception2 is a[WrongFormatException]
  }

  behavior of "ping/ping protocol"

  trait PingPongFixture extends BaseFixture { obj =>

    val builder = new TcpHandler.Builder {
      override def createTcpHandler(remote: InetSocketAddress, blockHandlers: AllHandlers)(
          implicit config: PlatformConfig): Props = {
        Props(new TcpHandler(remote, blockHandlers) {
          connection = obj.connection.ref
          override def receive: Receive = handleWith(ByteString.empty, handlePayload)
        })
      }
    }
    val tcpHandler = system.actorOf(builder.createTcpHandler(remote, blockHandlers))
  }

  it should "send ping after receiving SendPing" in new PingPongFixture {
    tcpHandler ! TcpHandler.SendPing
    connection.expectMsgPF() {
      case Tcp.Write(data, _) =>
        val message = Message.deserializer.deserialize(data).get
        message.payload is a[Ping]
    }
  }

  it should "replay pong to ping" in new PingPongFixture {
    val nonce    = Random.nextInt()
    val message1 = Message(Ping(nonce, System.currentTimeMillis()))
    val data1    = Message.serializer.serialize(message1)
    tcpHandler ! Tcp.Received(data1)
    connection.expectMsg(TcpHandler.envelope(Message(Pong(nonce))))
  }

  it should "fail if receive a wrong ping" in new PingPongFixture {
    watch(tcpHandler)
    val nonce    = Random.nextInt()
    val message1 = Message(Pong(nonce))
    val data1    = Message.serializer.serialize(message1)
    tcpHandler ! Tcp.Received(data1)
    expectTerminated(tcpHandler)
  }
}
