package org.alephium.protocol.message

import java.net.InetSocketAddress

import org.alephium.crypto.{ED25519, ED25519PrivateKey, ED25519PublicKey}
import org.alephium.protocol.config.DiscoveryConfig
import org.alephium.protocol.model.BrokerId
import org.alephium.util.{AVector, AlephiumSpec, EnumerationMacros}
import org.scalatest.EitherValues._

import scala.concurrent.duration._

class DiscoveryMessageSpec extends AlephiumSpec {
  import DiscoveryMessage.Code

  implicit val ordering: Ordering[Code[_]] = Ordering.by(Code.toInt(_))

  behavior of "DiscoveryMessage"

  it should "index all codes" in {
    val codes = EnumerationMacros.sealedInstancesOf[Code[_]]
    Code.values is AVector.from(codes)
  }

  // TODO: clean code
  trait DiscoveryConfigFixture { self =>
    def groups: Int
    def brokerNum: Int
    def groupNumPerBroker: Int
    def brokerId: BrokerId
    def isMaster: Boolean

    implicit val config: DiscoveryConfig = new DiscoveryConfig {
      val groups: Int            = self.groups
      val brokerNum: Int         = self.brokerNum
      val groupNumPerBroker: Int = self.groupNumPerBroker
      val brokerId: BrokerId     = self.brokerId
      val isMaster: Boolean      = self.isMaster

      def publicAddress: InetSocketAddress       = new InetSocketAddress(1)
      val (privateKey, publicKey)                = ED25519.generatePriPub()
      def discoveryPrivateKey: ED25519PrivateKey = privateKey
      def discoveryPublicKey: ED25519PublicKey   = publicKey

      val peersPerGroup: Int                = 1
      val scanMaxPerGroup: Int              = 1
      val scanFrequency: FiniteDuration     = 1.second
      val scanFastFrequency: FiniteDuration = 1.second
      val neighborsPerGroup: Int            = 1
    }
  }

  it should "support serde for all message types" in new DiscoveryConfigFixture {
    def groups: Int            = 4
    def brokerNum: Int         = 4
    def groupNumPerBroker: Int = 1
    def brokerId: BrokerId     = BrokerId.unsafe(0)
    def isMaster: Boolean      = true

    val peerFixture = new DiscoveryConfigFixture {
      def groups: Int            = 4
      def brokerNum: Int         = 4
      def groupNumPerBroker: Int = 1
      def brokerId: BrokerId     = BrokerId.unsafe(0)
      def isMaster: Boolean      = false
    }
    forAll(DiscoveryMessageGen.message(peerFixture.config)) { msg =>
      val bytes = DiscoveryMessage.serialize(msg)(peerFixture.config)
      val value = DiscoveryMessage.deserialize(bytes)(config).right.value
      msg == value
    }
  }
}
