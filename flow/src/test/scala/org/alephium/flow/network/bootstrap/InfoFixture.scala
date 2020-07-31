package org.alephium.flow.network.bootstrap

import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.ModelGenerators

trait InfoFixture extends ModelGenerators {
  def genIntraCliqueInfo(implicit config: GroupConfig): IntraCliqueInfo = {
    val info = cliqueInfoGen(config).sample.get
    val peers = info.peers.mapWithIndex { (address, id) =>
      PeerInfo.unsafe(id, info.groupNumPerBroker, address.getAddress, address.getPort, None, None)
    }
    IntraCliqueInfo.unsafe(info.id, peers, info.groupNumPerBroker)
  }
}
