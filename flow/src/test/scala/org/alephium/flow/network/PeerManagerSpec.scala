package org.alephium.flow.network

import akka.actor.{Props, Terminated}
import akka.testkit.{SocketUtil, TestProbe}
import org.alephium.flow.{Mode, PlatformConfig}
import org.alephium.flow.network.PeerManager.{GetPeers, PeerInfo}
import org.alephium.flow.storage.HandlerUtils
import org.alephium.protocol.message.{GetBlocks, Message}
import org.alephium.protocol.model.PeerId
import org.alephium.util.{AVector, AlephiumActorSpec}

class PeerManagerSpec extends AlephiumActorSpec("PeerManagerSpec") {

  trait PeerFixture extends PlatformConfig.Default {
    val remote     = SocketUtil.temporaryServerAddress()
    val peerId     = PeerId.generate
    val tcpHandler = TestProbe()
    val peerInfo   = PeerInfo(peerId, remote, tcpHandler.ref)
  }

  trait Fixture extends PeerFixture {
    val server          = TestProbe()
    val discoveryServer = TestProbe()
    val blockHandlers   = HandlerUtils.createBlockHandlersProbe
    val peerManager     = system.actorOf(PeerManager.props(Mode.defaultBuilders))

    peerManager ! PeerManager.Set(server.ref, blockHandlers, discoveryServer.ref)
  }

  behavior of "PeerManagerSpec"

  it should "add peer when received connection" in new Fixture {
    peerManager ! PeerManager.Connected(peerId, peerInfo)
    peerManager ! PeerManager.GetPeers
    expectMsgPF() {
      case PeerManager.Peers(peers) =>
        peers.sumBy(_.length) is 1
        peers(peerId.groupIndex.value).head.id is peerId
    }
  }

  it should "try to send GetBlocks to peer" in new PeerFixture {
    val server          = TestProbe()
    val discoveryServer = TestProbe()
    val blockHandlers   = HandlerUtils.createBlockHandlersProbe
    val peerManager = system.actorOf(Props(new PeerManager(Mode.defaultBuilders) {
      addPeer(peerInfo)
    }))
    peerManager ! PeerManager.Set(server.ref, blockHandlers, discoveryServer.ref)
    peerManager ! PeerManager.Sync(peerId, AVector.empty)
    tcpHandler.expectMsg(Message(GetBlocks(AVector.empty)))
  }

  it should "stop if server stopped" in new Fixture {
    watch(peerManager)
    server.expectMsg(TcpServer.Start)
    system.stop(server.ref)
    expectTerminated(peerManager)
  }

  it should "remove peer when tcp handler stopped" in new Fixture {
    watch(peerManager)
    peerManager ! PeerManager.Connected(peerId, peerInfo)
    peerManager ! PeerManager.GetPeers
    expectMsgPF() {
      case PeerManager.Peers(peers1) =>
        peers1.sumBy(_.length) is 1
        val peerInfo = peers1(peerId.groupIndex.value).head
        peerInfo.id is peerId
        val handler = peerInfo.tcpHandler
        watch(handler)
        system.stop(handler)
        expectMsgPF() {
          case message: Terminated =>
            peerManager ! message
            peerManager ! GetPeers
            expectMsgPF() {
              case PeerManager.Peers(peers2) =>
                peers2.sumBy(_.length) is 0
            }
        }
    }
  }
}
