package org.alephium.flow.network

import java.net.{InetAddress, InetSocketAddress}
import scala.concurrent.duration._

import akka.testkit.{SocketUtil, TestProbe}

import org.alephium.protocol.model.{PeerAddress, PeerId}
import org.alephium.util.AlephiumActorSpec

class DiscoveryServerSpec extends AlephiumActorSpec("DiscoveryServerSpec") {

  behavior of "DiscoveryServerSpec"

  it should "discover 32 peers with a mean 'rank' of 1" in {
    def createAddr(port: Int): InetSocketAddress =
      new InetSocketAddress(InetAddress.getLocalHost, port)

    def createConfig(port: Int, peerId: PeerId): DiscoveryConfig =
      DiscoveryConfig(port,
                      peerId,
                      peersMax      = 6,
                      scanMax       = 1,
                      neighborsMax  = 1,
                      scanFrequency = 1000.millis)

    val networkSize = 32
    def enumerate   = 0 until networkSize

    val peerIds = enumerate.map(_ => PeerId.generate)
    val ports   = enumerate.map(_ => SocketUtil.temporaryLocalPort(true))

    val bootstrapPeers = Set(PeerAddress(peerIds(0), createAddr(ports(0))))

    val probes = enumerate.map { i =>
      val config = createConfig(ports(i), peerIds(i))
      val probe  = TestProbe()
      val actor =
        system.actorOf(DiscoveryServer.props(config, Set(probe.ref)), ports(i).toString)
      actor ! DiscoveryServer.Bootstrap(if (i == 0) Set.empty else bootstrapPeers)
      probe
    }

    val discoveries = enumerate.par.map { i =>
      val xs = probes(i).receiveWhile(4.seconds, 2.seconds, 64) {
        case x: DiscoveryServer.Discovery => x
      }
      xs.last
    }

    val ranks = enumerate.map { i =>
      val peerId = peerIds(i)

      val discovereds = discoveries(i).peers.map(_.address.id).toSet
      val nearests    = peerIds.sortBy(PeerId.distance(peerId, _))

      val rank = nearests.zipWithIndex.collectFirst { case (id, i) if discovereds(id) => i }

      rank.getOrElse(0)
    }

    ranks.min is 1
    (ranks.sum / ranks.size) is 1
  }
}
