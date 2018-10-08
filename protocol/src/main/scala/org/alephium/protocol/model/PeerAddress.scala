package org.alephium.protocol.model

import java.net.InetSocketAddress

import org.alephium.serde.Serde

case class PeerAddress(id: PeerId, socketAddress: InetSocketAddress)
object PeerAddress {
  implicit val serde: Serde[PeerAddress] =
    Serde.forProduct2(PeerAddress.apply, p => (p.id, p.socketAddress))
}
