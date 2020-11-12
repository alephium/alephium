package org.alephium.api.model

import java.net.InetSocketAddress

import org.alephium.protocol.model.CliqueId

final case class InterCliquePeerInfo(cliqueId: CliqueId,
                                       brokerId: Int,
                                       address: InetSocketAddress,
                                       isSynced: Boolean)
