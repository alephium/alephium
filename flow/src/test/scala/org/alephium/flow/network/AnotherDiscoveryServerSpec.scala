package org.alephium.flow.network

import java.net.{InetAddress, InetSocketAddress}

import akka.testkit.{SocketUtil, TestProbe}
import org.alephium.protocol.config.{GroupConfig, GroupConfigFixture, DiscoveryConfig => DC}
import org.alephium.protocol.model.{GroupIndex, PeerId, PeerInfo}
import org.alephium.util.AlephiumActorSpec
import org.scalacheck.Gen

import scala.concurrent.duration._

object AnotherDiscoveryServerSpec {
  def createAddr(port: Int): InetSocketAddress =
    new InetSocketAddress(InetAddress.getLocalHost, port)

  def createConfig(groupSize: Int,
                   groupIndex: Int,
                   port: Int,
                   peersPerGroup: Int,
                   scanFrequency: FiniteDuration = 500.millis): DiscoveryConfig = {
    implicit val groupconfig = new GroupConfig {
      val groups = groupSize
    }
    val (privateKey, publicKey) = DC.generateDiscoveryKeyPair(GroupIndex(groupIndex))
    DiscoveryConfig(InetAddress.getLocalHost,
                    port,
                    groupSize,
                    privateKey,
                    publicKey,
                    peersPerGroup,
                    scanMax           = 1,
                    neighborsPerGroup = peersPerGroup,
                    scanFrequency     = scanFrequency)
  }
}

class AnotherDiscoveryServerSpec extends AlephiumActorSpec("DiscoveryServerSpec") {
  import AnotherDiscoveryServerSpec._

  it should "discovery each other for two nodes" in {
    val groupSize = Gen.choose(2, 10).sample.get
    val port0     = SocketUtil.temporaryLocalPort(udp = true)
    val config0   = createConfig(groupSize, 0, port0, 1)
    val server0   = system.actorOf(AnotherDiscoveryServer.props()(config0), "server0")
    val port1     = SocketUtil.temporaryLocalPort(udp = true)
    val config1   = createConfig(groupSize, 1, port1, 1)
    val bootstrap = PeerInfo(config0.peerId, createAddr(port0))
    val server1   = system.actorOf(AnotherDiscoveryServer.props(bootstrap)(config1), "server1")

    Thread.sleep(1000)

    val probo0 = TestProbe()
    server0.tell(AnotherDiscoveryServer.GetPeers, probo0.ref)
    val probo1 = TestProbe()
    server1.tell(AnotherDiscoveryServer.GetPeers, probo1.ref)
    probo0.expectMsgPF(40.seconds) {
      case AnotherDiscoveryServer.Peers(peers) =>
        peers.foreachWithIndex { (group, index) =>
          if (index == 1) {
            group.length is 1
            group.head is config1.nodeInfo
          } else {
            group.length is 0
          }
        }
    }
    probo1.expectMsgPF(40.seconds) {
      case AnotherDiscoveryServer.Peers(peers) =>
        peers.foreachWithIndex { (group, index) =>
          if (index == 0) {
            group.length is 1
            group.head is config0.nodeInfo
          } else {
            group.length is 0
          }
        }
    }
  }

  // TODO: move this to integration tests
  ignore should "discover peers for small network" in new GroupConfigFixture {
    val groups        = 8
    val networkSize   = 32
    val peersPerGroup = 2

    def enumerate = 0 until networkSize

    val configs = enumerate.map { i =>
      val groupIndex = i % groups
      val port       = 10000 + i
      createConfig(groups, groupIndex, port, peersPerGroup)
    }
    val peerIds = configs.map(_.peerId)
    val ports   = configs.map(_.udpPort)

    val bootstrap = PeerInfo(peerIds(0), createAddr(ports(0)))
    val actors = enumerate.map { i =>
      val config = configs(i)
      system.actorOf(AnotherDiscoveryServer.props(bootstrap)(config), ports(i).toString)
    }

    Thread.sleep(1000)

    val discoveries = enumerate.map { i =>
      actors(i) ! AnotherDiscoveryServer.GetPeers

      val AnotherDiscoveryServer.Peers(peers) = fishForMessage(10.seconds, "discovery") {
        case AnotherDiscoveryServer.Peers(peers) =>
          if (peers.forall(_.length >= peersPerGroup)) true
          else {
            actors(i) ! AnotherDiscoveryServer.GetPeers
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
        discoveries(i).flatMap(_.map(_.id)).sortBy(PeerId.distance(peerId, _))
      val nearests = peerIds.sortBy(PeerId.distance(peerId, _))
      val rank = nearests.zipWithIndex.collectFirst {
        case (id, i) if discovereds.contains(id) => i
      }

      rank.getOrElse(0) / networkSize.toDouble
    }

    ranks.min isnot 0.0
    assert((ranks.sum / ranks.size) < 0.10)
  }
}
