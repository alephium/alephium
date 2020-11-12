package org.alephium.api.model

import org.alephium.protocol.model.InterCliqueInfo
import org.alephium.util.AVector

final case class NeighborCliques(cliques: AVector[InterCliqueInfo])
