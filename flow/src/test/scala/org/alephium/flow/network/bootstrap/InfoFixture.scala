package org.alephium.flow.network.bootstrap

import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.ModelGen

trait InfoFixture {
  def genIntraCliqueInfo(implicit config: GroupConfig): IntraCliqueInfo = {
    val info = ModelGen.cliqueInfo.sample.get
    val peers = info.peers.mapWithIndex { (address, id) =>
      PeerInfo.unsafe(id, info.groupNumPerBroker, address.getAddress, address.getPort, None, None)
    }
    IntraCliqueInfo.unsafe(info.id, peers, info.groupNumPerBroker)
  }
}
