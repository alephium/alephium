package org.alephium.network

import akka.actor.Props
import akka.io.Tcp
import akka.testkit.{SocketUtil, TestProbe}
import akka.util.ByteString
import org.scalatest.TryValues._
import org.alephium.AlephiumActorSpec
import org.alephium.protocol.message.{Message, Ping, Pong, SendBlocks}
import org.alephium.serde.WrongFormatException

class TcpHandlerSpec extends AlephiumActorSpec("TcpHandlerSpec") {

  behavior of "TcpHandler"

  trait Fixture { obj =>
    val remote = SocketUtil.temporaryServerAddress()

    val message = Message(SendBlocks(Seq.empty))
    val data    = Message.serializer.serialize(message)

    val connection     = TestProbe()
    val blockPool      = TestProbe()
    val messageHandler = TestProbe()

    val tcpHandler = system.actorOf(
      Props(
        new TcpHandler(remote, connection.ref, blockPool.ref) {
          override val messageHandler = obj.messageHandler.ref
        }
      )
    )
    messageHandler.expectMsg(MessageHandler.SendPing)
  }

  it should "forward data to message handler" in new Fixture {
    tcpHandler ! Tcp.Received(data)
    messageHandler.expectMsg(message.payload)
  }

  it should "stop when received corrupted data" in new Fixture {
    watch(tcpHandler)
    tcpHandler ! Tcp.Received(data.tail)
    expectTerminated(tcpHandler)
  }

  it should "handle message boundary correctly" in new Fixture {
    tcpHandler ! Tcp.Received(data.take(1))
    tcpHandler ! Tcp.Received(data.tail)
    messageHandler.expectMsg(message.payload)
  }

  it should "stop when tcp connection closed" in new Fixture {
    watch(tcpHandler)
    tcpHandler ! Tcp.Closed
    expectTerminated(tcpHandler)
  }

  it should "stop when message handler stopped" in new Fixture {
    watch(tcpHandler)
    system.stop(messageHandler.ref)
    expectTerminated(tcpHandler)
  }

  it should "send data out when new message generated" in new Fixture {
    tcpHandler ! message
    connection.expectMsg(TcpHandler.envelope(message))
  }

  behavior of "Deserialization"

  trait SerdeFixture {
    val message1 = Message(Ping(1))
    val message2 = Message(Pong(2))
    val bytes1   = Message.serializer.serialize(message1)
    val bytes2   = Message.serializer.serialize(message2)
    val bytes    = bytes1 ++ bytes2
  }

  it should "deserialize two messages correctly" in new SerdeFixture {
    val result = TcpHandler.deserialize(bytes).success.value
    result._1 shouldBe Seq(message1, message2)
    result._2 shouldBe ByteString.empty
    for (n <- bytes.indices) {
      val input  = bytes.take(n)
      val output = TcpHandler.deserialize(input).success.value
      if (n < bytes1.length) {
        output._1 shouldBe Seq.empty
        output._2 shouldBe input
      } else {
        output._1 shouldBe Seq(message1)
        output._2 shouldBe input.drop(bytes1.length)
      }
    }
  }

  it should "fail when data is corrupted" in new SerdeFixture {
    val exception1 = TcpHandler.deserialize(bytes.tail).failure.exception
    exception1 shouldBe a[WrongFormatException]
    val exception2 = TcpHandler.deserialize(bytes1 ++ bytes2.tail).failure.exception
    exception2 shouldBe a[WrongFormatException]
  }
}
