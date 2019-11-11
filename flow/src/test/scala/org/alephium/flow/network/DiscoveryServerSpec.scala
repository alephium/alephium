package org.alephium.flow.network

import java.net.{InetAddress, InetSocketAddress}

import akka.testkit.{SocketUtil, TestProbe}
import org.scalacheck.Gen

import org.alephium.crypto.ED25519
import org.alephium.protocol.config.{DiscoveryConfig, GroupConfig, GroupConfigFixture}
import org.alephium.protocol.model.{CliqueInfo, ModelGen}
import org.alephium.util.{AlephiumActorSpec, Duration}

object DiscoveryServerSpec {
  def createAddr(port: Int): InetSocketAddress =
    new InetSocketAddress(InetAddress.getLocalHost, port)

  def createConfig(groupSize: Int,
                   port: Int,
                   _peersPerGroup: Int,
                   _scanFrequency: Duration = Duration.ofMillis(500)): DiscoveryConfig = {
    new DiscoveryConfig {
      val publicAddress: InetSocketAddress          = new InetSocketAddress("localhost", port)
      val (discoveryPrivateKey, discoveryPublicKey) = ED25519.generatePriPub()

      val peersPerGroup: Int          = _peersPerGroup
      val scanMaxPerGroup: Int        = 1
      val scanFrequency: Duration     = _scanFrequency
      val scanFastFrequency: Duration = _scanFrequency
      val neighborsPerGroup: Int      = _peersPerGroup

      val groups: Int            = groupSize
      val brokerNum: Int         = groupSize
      val groupNumPerBroker: Int = 1
    }
  }
}

class DiscoveryServerSpec extends AlephiumActorSpec("DiscoveryServerSpec") {
  import DiscoveryServerSpec._

  def generateCliqueInfo(master: InetSocketAddress)(implicit config: GroupConfig): CliqueInfo = {
    val randomInfo = ModelGen.cliqueInfo.sample.get
    val newPeers   = randomInfo.peers.replace(0, master)
    val newInfo    = randomInfo.copy(peers = newPeers)
    newInfo.masterAddress is master
    newInfo
  }

  it should "discovery each other for two cliques" in new GroupConfigFixture {
    val groups      = Gen.choose(2, 10).sample.get
    val port0       = SocketUtil.temporaryLocalPort(udp = true)
    val cliqueInfo0 = generateCliqueInfo(createAddr(port0))
    val config0     = createConfig(groups, port0, 1)
    val server0     = system.actorOf(DiscoveryServer.props()(config0), "server0")
    val port1       = SocketUtil.temporaryLocalPort(udp = true)
    val cliqueInfo1 = generateCliqueInfo(createAddr(port1))
    val config1     = createConfig(groups, port1, 1)
    val server1     = system.actorOf(DiscoveryServer.props(createAddr(port0))(config1), "server1")

    server0 ! cliqueInfo0
    server1 ! cliqueInfo1

    Thread.sleep(2000)

    val probo0 = TestProbe()
    server0.tell(DiscoveryServer.GetPeerCliques, probo0.ref)
    val probo1 = TestProbe()
    server1.tell(DiscoveryServer.GetPeerCliques, probo1.ref)

    val waitTime = Duration.ofSeconds(40).asScala
    probo0.expectMsgPF(waitTime) {
      case DiscoveryServer.PeerCliques(peers) =>
        peers.length is 1
        peers.head is cliqueInfo1
    }
    probo1.expectMsgPF(waitTime) {
      case DiscoveryServer.PeerCliques(peers) =>
        peers.length is 1
        peers.head is cliqueInfo0
    }
  }
}
