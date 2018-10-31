package org.alephium.protocol.model

import java.net.InetSocketAddress

import org.alephium.protocol.config.GroupConfig
import org.alephium.serde.Serde

case class PeerInfo(id: PeerId, socketAddress: InetSocketAddress) {
  def group(implicit config: GroupConfig): GroupIndex = id.groupIndex
}

object PeerInfo {
  implicit val serde: Serde[PeerInfo] =
    Serde.forProduct2(PeerInfo.apply, p => (p.id, p.socketAddress))
}
