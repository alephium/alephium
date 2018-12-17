package org.alephium.protocol.config
import org.alephium.protocol.model.PeerId

trait DiscoveryConfigFixture { self =>
  def groups: Int

  implicit val config: DiscoveryConfig = new DiscoveryConfig {
    override def peerId: PeerId = PeerId.generate

    def groups: Int = self.groups
  }

}
