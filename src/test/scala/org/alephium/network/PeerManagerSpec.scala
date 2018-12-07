package org.alephium.network

import akka.actor.{Props, Terminated}
import akka.io.Tcp
import akka.testkit.{SocketUtil, TestProbe}
import org.alephium.AlephiumActorSpec
import org.alephium.network.PeerManager.GetPeers
import org.alephium.protocol.message.{GetBlocks, Message}

class PeerManagerSpec extends AlephiumActorSpec("PeerManagerSpec") {

  trait Fixture {
    val blockPool   = TestProbe()
    val port        = SocketUtil.temporaryLocalPort()
    val peerManager = system.actorOf(PeerManager.props(port, blockPool.ref))
  }

  behavior of "PeerManagerSpec"

  // TODO: add tests for connecting to server

  it should "add peer when received connection" in new Fixture {
    val remote     = SocketUtil.temporaryServerAddress()
    val local      = SocketUtil.temporaryServerAddress()
    val connection = TestProbe()
    peerManager.tell(Tcp.Connected(remote, local), connection.ref)
    peerManager ! PeerManager.GetPeers
    expectMsgPF() {
      case PeerManager.Peers(peers) =>
        peers.size shouldBe 1
        peers.head._1 shouldBe remote
    }
  }

  it should "not add peer when connect failed" in new Fixture {
    val remote  = SocketUtil.temporaryServerAddress()
    val connect = Tcp.Connect(remote)
    peerManager ! Tcp.CommandFailed(connect)
    peerManager ! PeerManager.GetPeers
    expectMsg(PeerManager.Peers(Map.empty))
  }

  it should "try to send GetBlocks to peer" in {
    val blockPool  = TestProbe()
    val port       = SocketUtil.temporaryLocalPort()
    val remote     = SocketUtil.temporaryServerAddress()
    val tcpHandler = TestProbe()
    val peerManager = system.actorOf(Props(new PeerManager(port, blockPool.ref) {
      override def receive: Receive = manage(Map(remote -> tcpHandler.ref))
    }))
    peerManager ! PeerManager.Sync(remote, Seq.empty)
    tcpHandler.expectMsg(Message(GetBlocks(Seq.empty)))
  }

  it should "stop if block pool stoped" in new Fixture {
    watch(peerManager)
    system.stop(blockPool.ref)
    expectTerminated(peerManager)
  }

  it should "stop if server stopped" in new Fixture {
    override val peerManager = system.actorOf(Props(new PeerManager(port, blockPool.ref) {
      override def postStop(): Unit = {
        testActor ! "stop"
      }
    }))
    expectMsg("stop")
  }

  it should "remove peer when tcp handler stopped" in new Fixture {
    val remote     = SocketUtil.temporaryServerAddress()
    val local      = SocketUtil.temporaryServerAddress()
    val connection = TestProbe()
    watch(peerManager)
    peerManager.tell(Tcp.Connected(remote, local), connection.ref)
    peerManager ! PeerManager.GetPeers
    expectMsgPF() {
      case PeerManager.Peers(peers1) =>
        peers1.size shouldBe 1
        peers1.head._1 shouldBe remote
        val handler = peers1.head._2
        watch(handler)
        system.stop(handler)
        expectMsgPF() {
          case message: Terminated =>
            peerManager ! message
            peerManager ! GetPeers
            expectMsgPF() {
              case PeerManager.Peers(peers2) =>
                peers2.isEmpty shouldBe true
            }
        }
    }
  }
}
