package org.alephium.network

import akka.actor.Props
import akka.io.Tcp
import akka.testkit.{SocketUtil, TestProbe}
import org.alephium.AlephiumActorSpec
import org.alephium.protocol.message.{Message, SendBlocks}

class TcpHandlerSpec extends AlephiumActorSpec("TcpHandlerSpec") {

  trait Fixture { obj =>
    val remote = SocketUtil.temporaryServerAddress()

    val message = Message(SendBlocks(Seq.empty))
    val data    = Message.serializer.serialize(message)

    lazy val connection     = TestProbe()
    lazy val blockPool      = TestProbe()
    lazy val messageHandler = TestProbe()
    lazy val tcpHandler = system.actorOf(
      Props(
        new TcpHandler(remote, connection.ref, blockPool.ref) {
          override val messageHandler = obj.messageHandler.ref

          override def preStart(): Unit = {}
        }
      )
    )

  }

  behavior of "TcpHandler"

  it should "forward data to message handler" in new Fixture {
    tcpHandler ! Tcp.Received(data)
    messageHandler.expectMsg(message.payload)
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
}
