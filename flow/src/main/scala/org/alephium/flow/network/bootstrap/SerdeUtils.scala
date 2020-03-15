package org.alephium.flow.network.bootstrap

import org.alephium.flow.platform.PlatformConfig
import org.alephium.serde.Serde

trait SerdeUtils {
  implicit def config: PlatformConfig
  implicit val peerInfoSerde: Serde[PeerInfo]          = PeerInfo._serde
  implicit val intraCliqueInfo: Serde[IntraCliqueInfo] = IntraCliqueInfo._serde
}
