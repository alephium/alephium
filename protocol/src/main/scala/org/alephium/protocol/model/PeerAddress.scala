package org.alephium.protocol.model

import java.net.InetSocketAddress

import org.alephium.serde.Serde

case class PeerAddress(
    peerId: PeerId,
    group: GroupIndex,
    socketAddress: InetSocketAddress
)
object PeerAddress {
  implicit val serde: Serde[PeerAddress] =
    Serde.forProduct3(PeerAddress.apply, p => (p.peerId, p.group, p.socketAddress))
}
