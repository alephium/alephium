package org.alephium.flow.network.bootstrap

import org.alephium.protocol.model.ModelGenerators

trait InfoFixture extends ModelGenerators {
  def genIntraCliqueInfo: IntraCliqueInfo = {
    val info = cliqueInfoGen.sample.get
    val peers = info.internalAddresses.mapWithIndex { (address, id) =>
      PeerInfo.unsafe(id, info.groupNumPerBroker, Some(address), address, None, None)
    }
    IntraCliqueInfo.unsafe(info.id, peers, info.groupNumPerBroker)
  }
}
