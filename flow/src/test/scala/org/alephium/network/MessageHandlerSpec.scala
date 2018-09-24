package org.alephium.network

import java.net.InetSocketAddress

import akka.io.Tcp
import akka.testkit.{SocketUtil, TestProbe}
import org.alephium.AlephiumActorSpec
import org.alephium.protocol.message._
import org.alephium.storage.HandlerUtils

import scala.util.Random

class MessageHandlerSpec
    extends AlephiumActorSpec("MessageHandlerSpec")
    with MessageHandler.Builder {

  trait Fixture {
    lazy val remote        = new InetSocketAddress(SocketUtil.temporaryLocalPort())
    lazy val connection    = TestProbe()
    lazy val blockHandlers = HandlerUtils.createBlockHandlersProbe

    lazy val messageHandler =
      system.actorOf(createMessageHandler(remote, connection.ref, blockHandlers))
  }

  behavior of "MessageHandlerSpec"

  it should "send ping after receiving SendPing" in new Fixture {
    messageHandler ! MessageHandler.SendPing
    connection.expectMsgPF() {
      case Tcp.Write(data, _) =>
        val message = Message.deserializer.deserialize(data).get
        message.payload is a[Ping]
    }
  }

  it should "replay pong to ping" in new Fixture {
    val nonce = Random.nextInt()
    messageHandler ! Ping(nonce, System.currentTimeMillis())
    connection.expectMsg(TcpHandler.envelope(Message(Pong(nonce))))
  }

  it should "fail if receive a wrong ping" in new Fixture {
    watch(messageHandler)
    messageHandler ! Pong(1)
    expectTerminated(messageHandler)
  }

//  it should "add blocks when receiving new blocks" in new Fixture {
//    messageHandler ! SendBlocks(Seq.empty)
//    blockHandler.expectMsg(BlockHandler.AddBlocks(Seq.empty))
//  }
//
//  it should "send blocks when asked for new blocks" in new Fixture {
//    messageHandler ! GetBlocks(Seq.empty)
//    blockHandler.expectMsg(BlockHandler.GetBlocksAfter(Seq.empty))
//  }
}
