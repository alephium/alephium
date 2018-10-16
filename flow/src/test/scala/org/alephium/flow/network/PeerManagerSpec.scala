package org.alephium.flow.network

import akka.actor.{Props, Terminated}
import akka.io.Tcp
import akka.testkit.{SocketUtil, TestProbe}
import org.alephium.flow.{Mode, PlatformConfig}
import org.alephium.flow.network.PeerManager.GetPeers
import org.alephium.flow.storage.HandlerUtils
import org.alephium.protocol.message.{GetBlocks, Message}
import org.alephium.util.AlephiumActorSpec

class PeerManagerSpec extends AlephiumActorSpec("PeerManagerSpec") {

  trait Fixture extends PlatformConfig.Default {
    val server        = TestProbe()
    val blockHandlers = HandlerUtils.createBlockHandlersProbe
    val peerManager   = system.actorOf(PeerManager.props(Mode.defaultBuilders))

    peerManager ! PeerManager.Set(server.ref, blockHandlers)
  }

  behavior of "PeerManagerSpec"

  // TODO: add tests for connecting to server

  it should "add peer when received connection" in new Fixture {
    val remote     = SocketUtil.temporaryServerAddress()
    val connection = TestProbe()
    peerManager ! PeerManager.Connected(remote, connection.ref)
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

  it should "try to send GetBlocks to peer" in new PlatformConfig.Default {
    val remote     = SocketUtil.temporaryServerAddress()
    val tcpHandler = TestProbe()

    val server        = TestProbe()
    val blockHandlers = HandlerUtils.createBlockHandlersProbe
    val peerManager = system.actorOf(Props(new PeerManager(Mode.defaultBuilders) {
      peers += (remote -> tcpHandler.ref)
    }))
    peerManager ! PeerManager.Set(server.ref, blockHandlers)
    peerManager ! PeerManager.Sync(remote, Seq.empty)
    tcpHandler.expectMsg(Message(GetBlocks(Seq.empty)))
  }

  it should "stop if server stopped" in new Fixture {
    watch(peerManager)
    server.expectMsg(TcpServer.Start)
    system.stop(server.ref)
    expectTerminated(peerManager)
  }

  it should "remove peer when tcp handler stopped" in new Fixture {
    val remote     = SocketUtil.temporaryServerAddress()
    val connection = TestProbe()
    watch(peerManager)
    peerManager ! PeerManager.Connected(remote, connection.ref)
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
