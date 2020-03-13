package org.alephium.flow.network.bootstrap

import java.net.InetSocketAddress

import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{CliqueId, CliqueInfo}
import org.alephium.util.AVector

class IntraCliqueInfo(val id: CliqueId, val peers: AVector[PeerInfo], val groupNumPerBroker: Int) {
  def simple(implicit config: GroupConfig): CliqueInfo = {
    val addresses = peers.map(info => new InetSocketAddress(info.address, info.tcpPort))
    CliqueInfo.unsafe(id, addresses, groupNumPerBroker)
  }
}

object IntraCliqueInfo {
  def unsafe(id: CliqueId, peers: AVector[PeerInfo], groupNumPerBroker: Int)(
      implicit config: GroupConfig): IntraCliqueInfo = {
    assume(peers.length * groupNumPerBroker == config.groups)
    new IntraCliqueInfo(id, peers, groupNumPerBroker)
  }
}
