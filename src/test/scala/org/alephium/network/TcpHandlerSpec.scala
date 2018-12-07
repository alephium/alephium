package org.alephium.network

import akka.actor.Props
import akka.io.Tcp
import akka.testkit.{SocketUtil, TestProbe}
import org.scalatest.TryValues._
import org.alephium.AlephiumActorSpec
import org.alephium.protocol.message.{Message, Ping, Pong, SendBlocks}
import org.alephium.serde.{NotEnoughBytesException, WrongFormatException}

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

  behavior of "sequentialDeserialize"

  trait SerdeFixture {
    val message1 = Message(Ping(1))
    val message2 = Message(Pong(2))
    val bytes1   = Message.serializer.serialize(message1)
    val bytes2   = Message.serializer.serialize(message2)
    val bytes    = bytes1 ++ bytes2
  }

  it should "deserialize two messages correctly" in new SerdeFixture {
    TcpHandler.sequentialDeserialize(bytes).success.value shouldBe Seq(message1, message2)
  }

  it should "fail when data is corrupted" in new SerdeFixture {
    val exception1 = TcpHandler.sequentialDeserialize(bytes.tail).failure.exception
    exception1 shouldBe a[WrongFormatException]
    val exception2 = TcpHandler.sequentialDeserialize(bytes.init).failure.exception
    exception2 shouldBe a[NotEnoughBytesException]
  }
}
