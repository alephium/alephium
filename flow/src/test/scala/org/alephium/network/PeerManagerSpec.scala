package org.alephium.network

import akka.actor.{Props, Terminated}
import akka.io.Tcp
import akka.testkit.{SocketUtil, TestProbe}
import org.alephium.{AlephiumActorSpec, Mode}
import org.alephium.network.PeerManager.GetPeers
import org.alephium.protocol.message.{GetBlocks, Message}
import org.alephium.storage.HandlerUtils

class PeerManagerSpec extends AlephiumActorSpec("PeerManagerSpec") {

  trait Fixture {
    val blockHandlers = HandlerUtils.createBlockHandlersProbe
    val port          = SocketUtil.temporaryLocalPort()
    val peerManager   = system.actorOf(Props(new PeerManager(Mode.defaultBuilders, port)))

    peerManager ! PeerManager.SetBlockHandlers(blockHandlers)
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
        peers.size is 1
        peers.head._1 is remote
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
    val port       = SocketUtil.temporaryLocalPort()
    val remote     = SocketUtil.temporaryServerAddress()
    val tcpHandler = TestProbe()

    val blockHandlers = HandlerUtils.createBlockHandlersProbe
    val peerManager = system.actorOf(Props(new PeerManager(Mode.defaultBuilders, port) {
      peers += (remote -> tcpHandler.ref)
    }))
    peerManager ! PeerManager.SetBlockHandlers(blockHandlers)
    peerManager ! PeerManager.Sync(remote, Seq.empty)
    tcpHandler.expectMsg(Message(GetBlocks(Seq.empty)))
  }

  it should "stop if block pool stoped" in new Fixture {
    watch(peerManager)
    system.stop(blockHandlers.flowHandler)
    expectTerminated(peerManager)
  }

  it should "stop if server stopped" in {
    val port = SocketUtil.temporaryLocalPort()
    system.actorOf(TcpServer.props(port))

    val peerManager = system.actorOf(Props(new PeerManager(Mode.defaultBuilders, port)))
    watch(peerManager)
    expectTerminated(peerManager)
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
        peers1.size is 1
        peers1.head._1 is remote
        val handler = peers1.head._2
        watch(handler)
        system.stop(handler)
        expectMsgPF() {
          case message: Terminated =>
            peerManager ! message
            peerManager ! GetPeers
            expectMsgPF() {
              case PeerManager.Peers(peers2) =>
                peers2.isEmpty is true
            }
        }
    }
  }
}
