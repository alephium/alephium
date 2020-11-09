package org.alephium.api.model

import org.alephium.protocol.model.CliqueId
import org.alephium.util.AVector

final case class SelfClique(cliqueId: CliqueId,
                              peers: AVector[PeerAddress],
                              groupNumPerBroker: Int)
