package org.alephium.flow.network.bootstrap

import org.alephium.serde.Serde

trait SerdeUtils {
  implicit val peerInfoSerde: Serde[PeerInfo]          = PeerInfo._serde
  implicit val intraCliqueInfo: Serde[IntraCliqueInfo] = IntraCliqueInfo._serde
}
