package org.alephium.protocol.config

import org.alephium.protocol.model.{GroupIndex, PeerId}

trait DiscoveryConfig extends GroupConfig {

  def peerId: PeerId

  def group: GroupIndex = peerId.groupIndex(this)
}
