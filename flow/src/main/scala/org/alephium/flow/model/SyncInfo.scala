package org.alephium.flow.model

import org.alephium.protocol.Hash
import org.alephium.util.AVector

final case class SyncInfo(blockLocators: AVector[Hash], headerLocators: AVector[Hash])
