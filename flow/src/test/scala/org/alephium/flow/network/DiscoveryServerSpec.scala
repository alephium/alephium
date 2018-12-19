package org.alephium.flow.network

import java.net.{InetAddress, InetSocketAddress}

import org.alephium.protocol.config.GroupConfigFixture

import scala.concurrent.duration._
import org.alephium.protocol.model.{GroupIndex, PeerId, PeerInfo}
import org.alephium.util.{AVector, AlephiumActorSpec}

class DiscoveryServerSpec extends AlephiumActorSpec("DiscoveryServerSpec") {

  behavior of "DiscoveryServerSpec"

  it should "discover 128 peers in 8 groups with a mean distance below 0.05" in new GroupConfigFixture {
    val groups        = 8
    val networkSize   = 128
    val peersPerGroup = 1

    def createAddr(port: Int): InetSocketAddress =
      new InetSocketAddress(InetAddress.getLocalHost, port)

    def createConfig(port: Int, peerId: PeerId): DiscoveryConfig =
      DiscoveryConfig(InetAddress.getLocalHost,
                      port,
                      groups,
                      peerId,
                      peersPerGroup,
                      scanMax           = 1,
                      neighborsPerGroup = 1,
                      scanFrequency     = 500.millis)

    def enumerate = 0 until networkSize

    val peerIds = enumerate.map(i => PeerId.generateFor(GroupIndex(i % groups)))
    val ports   = enumerate.map(i => 10000 + i)

    val bootstrapPeers = AVector(PeerInfo(peerIds(0), createAddr(ports(0))))

    val actors = enumerate.map { i =>
      val config = createConfig(ports(i), peerIds(i))
      system.actorOf(DiscoveryServer.props(if (i == 0) AVector.empty else bootstrapPeers)(config),
                     ports(i).toString)
    }

    val discoveries = enumerate.map { i =>
      actors(i) ! DiscoveryServer.GetPeers

      val DiscoveryServer.Peers(peers) = fishForMessage(10.seconds, "discovery") {
        case DiscoveryServer.Peers(peers) =>
          if (peers.flatMap(identity).length >= groups * peersPerGroup) true
          else {
            actors(i) ! DiscoveryServer.GetPeers
            false
          }
      }

      peers
    }

    // Ensure total group diversity
    enumerate.foreach { i =>
      discoveries(i).foreach { xs =>
        xs.length is peersPerGroup
      }
    }

    // Compute distance ranking (1.0 farest, 0.0 closest)
    val ranks = enumerate.map { i =>
      val peerId = peerIds(i)

      val discovereds =
        discoveries(i).flatMap(_.map(_.info.id)).sortBy(PeerId.distance(peerId, _))
      val nearests = peerIds.sortBy(PeerId.distance(peerId, _))
      val rank = nearests.zipWithIndex.collectFirst {
        case (id, i) if discovereds.contains(id) => i
      }

      (rank.getOrElse(0) / networkSize.toDouble)
    }

    ranks.min isnot 0.0
    ((ranks.sum / ranks.size) < 0.05)
  }
}
