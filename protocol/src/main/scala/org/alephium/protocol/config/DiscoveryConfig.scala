package org.alephium.protocol.config

import org.alephium.crypto.{ED25519, ED25519PrivateKey, ED25519PublicKey}
import org.alephium.protocol.model.{GroupIndex, PeerId}

import scala.annotation.tailrec

object DiscoveryConfig {
  def generateDiscoveryKeyPair(groupIndex: GroupIndex)(
      implicit config: GroupConfig): (ED25519PrivateKey, ED25519PublicKey) = {
    @tailrec
    def iter(): (ED25519PrivateKey, ED25519PublicKey) = {
      val keyPair   = ED25519.generateKeyPair()
      val publicKey = keyPair._2
      val keyIndex  = GroupIndex((publicKey.bytes.last & 0xFF) % config.groups)
      if (keyIndex == groupIndex) keyPair else iter()
    }
    iter()
  }
}

trait DiscoveryConfig extends GroupConfig {

  def discoveryPrivateKey: ED25519PrivateKey

  def discoveryPublicKey: ED25519PublicKey

  val nodeId: PeerId = PeerId.fromPublicKey(discoveryPublicKey)

  val group: GroupIndex = nodeId.groupIndex(this)
}
