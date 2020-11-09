package org.alephium.api.model

import java.net.InetAddress

final case class PeerAddress(address: InetAddress, rpcPort: Int, restPort: Int, wsPort: Int)
