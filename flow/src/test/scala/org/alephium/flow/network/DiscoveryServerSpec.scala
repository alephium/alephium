package org.alephium.flow.network

import java.net.{InetAddress, InetSocketAddress}

import org.alephium.crypto.ED25519
import org.alephium.protocol.config.DiscoveryConfig
import org.alephium.protocol.model.{BrokerId, CliqueId}
import org.alephium.util.AlephiumActorSpec

import scala.concurrent.duration._

object DiscoveryServerSpec {
  def createAddr(port: Int): InetSocketAddress =
    new InetSocketAddress(InetAddress.getLocalHost, port)

  def createConfig(groupSize: Int,
                   groupIndex: Int,
                   port: Int,
                   _peersPerGroup: Int,
                   _scanFrequency: FiniteDuration = 500.millis): DiscoveryConfig = {
    new DiscoveryConfig {
      val publicAddress: InetSocketAddress          = new InetSocketAddress(port)
      val (discoveryPrivateKey, discoveryPublicKey) = ED25519.generatePriPub()

      val peersPerGroup: Int                = _peersPerGroup
      val scanMaxPerGroup: Int              = 1
      val scanFrequency: FiniteDuration     = _scanFrequency
      val scanFastFrequency: FiniteDuration = _scanFrequency
      val neighborsPerGroup: Int            = _peersPerGroup

      val groups: Int            = groupSize
      val brokerNum: Int         = groupSize
      val groupNumPerBroker: Int = 1
      val cliqueId: CliqueId     = CliqueId.generate
      val brokerId: BrokerId     = BrokerId.unsafe(groupIndex)
      val isMaster: Boolean      = false
    }
  }
}

class DiscoveryServerSpec extends AlephiumActorSpec("DiscoveryServerSpec") {}
