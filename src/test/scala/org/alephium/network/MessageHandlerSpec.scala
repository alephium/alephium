package org.alephium.network

import akka.actor.Terminated
import akka.io.Tcp
import akka.testkit.TestProbe
import org.alephium.AlephiumActorSpec
import org.alephium.protocol.message._
import org.alephium.storage.BlockPool

import scala.util.Random

class MessageHandlerSpec extends AlephiumActorSpec("MessageHandlerSpec") {

  trait Fixture {
    lazy val connection     = TestProbe()
    lazy val blockPool      = TestProbe()
    lazy val messageHandler = system.actorOf(MessageHandler.props(connection.ref, blockPool.ref))
  }

  behavior of "MessageHandlerSpec"

  it should "send ping after receiving SendPing" in new Fixture {
    messageHandler ! MessageHandler.SendPing
    connection.expectMsgPF() {
      case Tcp.Write(data, _) =>
        val message = Message.deserializer.deserialize(data).get
        message.payload shouldBe a[Ping]
    }
  }

  it should "replay pong to ping" in new Fixture {
    val nonce = Random.nextInt()
    messageHandler ! Ping(nonce)
    connection.expectMsg(TcpHandler.envelope(Message(Pong(nonce))))
  }

  it should "fail if receive a wrong ping" in new Fixture {
    watch(messageHandler)
    messageHandler ! Pong(1)
    expectMsgPF() {
      case Terminated(actor) => actor shouldBe messageHandler
    }
  }

  it should "let block pool add blocks when receiving new blocks" in new Fixture {
    messageHandler ! SendBlocks(Seq.empty)
    blockPool.expectMsg(BlockPool.AddBlocks(Seq.empty))
  }

  it should "let block pool send blocks when asked for new blocks" in new Fixture {
    messageHandler ! GetBlocks(Seq.empty)
    blockPool.expectMsg(BlockPool.GetBlocks(Seq.empty))
  }

  it should "send blocks to connection when receiving new blocks from block pool" in new Fixture {
    messageHandler ! BlockPool.SendBlocks(Seq.empty)
    connection.expectMsg(TcpHandler.envelope(Message(SendBlocks(Seq.empty))))
  }
}
