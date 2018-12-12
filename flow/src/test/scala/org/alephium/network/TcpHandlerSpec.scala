package org.alephium.network

import java.net.InetSocketAddress

import akka.actor.Props
import akka.io.Tcp
import akka.testkit.{SocketUtil, TestProbe}
import akka.util.ByteString
import org.scalatest.TryValues._
import org.alephium.AlephiumActorSpec
import org.alephium.protocol.message._
import org.alephium.serde.WrongFormatException
import org.alephium.storage.{BlockHandlers, HandlerUtils}

import scala.util.Random

class TcpHandlerSpec extends AlephiumActorSpec("TcpHandlerSpec") {

  behavior of "TcpHandler"

  trait Fixture { obj =>
    val remote = SocketUtil.temporaryServerAddress()

    val message = Message(SendBlocks(Seq.empty))
    val data    = Message.serializer.serialize(message)

    val connection    = TestProbe()
    val blockHandlers = HandlerUtils.createBlockHandlersProbe

    val payloadHandler = TestProbe()
    val builder = new TcpHandler.Builder {
      override def createTcpHandler(remote: InetSocketAddress,
                                    blockHandlers: BlockHandlers): Props = {
        Props(new TcpHandler(remote, blockHandlers) {
          override def handlePayload(payload: Payload): Unit = payloadHandler.ref ! payload
        })
      }
    }
    val tcpHandler = system.actorOf(builder.createTcpHandler(remote, blockHandlers))
    tcpHandler ! TcpHandler.Set(connection.ref)
    connection.expectMsgType[Tcp.Register]
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
    connection.expectMsgType[Tcp.Write] // Ping message
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
    result._1 is Seq(message1, message2)
    result._2 is ByteString.empty
    for (n <- bytes.indices) {
      val input  = bytes.take(n)
      val output = TcpHandler.deserialize(input).success.value
      if (n < bytes1.length) {
        output._1 is Seq.empty
        output._2 is input
      } else {
        output._1 is Seq(message1)
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

  trait PingPongFixture { obj =>
    val remote        = SocketUtil.temporaryServerAddress()
    val connection    = TestProbe()
    val blockHandlers = HandlerUtils.createBlockHandlersProbe
    val builder       = new TcpHandler.Builder {}
    val tcpHandler    = system.actorOf(builder.createTcpHandler(remote, blockHandlers))
    tcpHandler ! TcpHandler.Set(connection.ref)
    connection.expectMsgType[Tcp.Register]
  }

  it should "send ping after receiving SendPing" in new PingPongFixture {
    connection.expectMsgPF() {
      case Tcp.Write(data, _) =>
        val message = Message.deserializer.deserialize(data).get
        message.payload is a[Ping]
    }
  }

  it should "replay pong to ping" in new PingPongFixture {
    connection.expectMsgType[Tcp.Write]
    val nonce   = Random.nextInt()
    val message = Message(Ping(nonce, System.currentTimeMillis()))
    val data    = Message.serializer.serialize(message)
    tcpHandler ! Tcp.Received(data)
    connection.expectMsg(TcpHandler.envelope(Message(Pong(nonce))))
  }

  it should "fail if receive a wrong ping" in new PingPongFixture {
    watch(tcpHandler)
    val nonce   = Random.nextInt()
    val message = Message(Pong(nonce))
    val data    = Message.serializer.serialize(message)
    tcpHandler ! Tcp.Received(data)
    expectTerminated(tcpHandler)
  }
}
