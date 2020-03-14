package org.alephium.flow.network.bootstrap

import java.net.InetSocketAddress

import org.alephium.protocol.model.{CliqueId, CliqueInfo}
import org.alephium.util.AVector

sealed abstract case class IntraCliqueInfo(
    id: CliqueId,
    peers: AVector[PeerInfo],
    groupNumPerBroker: Int
) {
  def simple: CliqueInfo = {
    val addresses = peers.map(info => new InetSocketAddress(info.address, info.tcpPort))
    CliqueInfo.unsafe(id, addresses, groupNumPerBroker)
  }
}

object IntraCliqueInfo {
  def unsafe(id: CliqueId, peers: AVector[PeerInfo], groupNumPerBroker: Int): IntraCliqueInfo = {
    new IntraCliqueInfo(id, peers, groupNumPerBroker) {}
  }
}
