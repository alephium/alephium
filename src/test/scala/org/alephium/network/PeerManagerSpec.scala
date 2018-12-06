package org.alephium.network

import akka.io.Tcp
import akka.testkit.{SocketUtil, TestProbe}
import org.alephium.AlephiumActorSpec

class PeerManagerSpec extends AlephiumActorSpec("PeerManagerSpec") {

  trait Fixture {
    lazy val blockPool   = TestProbe()
    lazy val peerManager = system.actorOf(PeerManager.props(blockPool.ref))
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
}
