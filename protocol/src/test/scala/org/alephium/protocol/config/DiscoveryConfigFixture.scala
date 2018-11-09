package org.alephium.protocol.config
import org.alephium.crypto.{ED25519, ED25519PrivateKey, ED25519PublicKey}

trait DiscoveryConfigFixture { self =>
  def groups: Int
  private val (privateKey, publicKey) = ED25519.generateKeyPair()

  implicit val config: DiscoveryConfig = new DiscoveryConfig {

    def discoveryPrivateKey: ED25519PrivateKey = privateKey

    def discoveryPublicKey: ED25519PublicKey = publicKey

    def groups: Int = self.groups
  }

}
