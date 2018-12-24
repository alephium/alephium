package org.alephium.protocol.config

import org.alephium.crypto.{ED25519PrivateKey, ED25519PublicKey}
import org.alephium.protocol.model.{GroupIndex, PeerId}

trait DiscoveryConfig extends GroupConfig {

  def discoveryPrivateKey: ED25519PrivateKey

  def discoveryPublicKey: ED25519PublicKey

  val nodeId: PeerId = PeerId.fromPublicKey(discoveryPublicKey)

  val group: GroupIndex = nodeId.groupIndex(this)
}
