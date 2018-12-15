package org.alephium.flow.model

import org.alephium.crypto.Keccak256
import org.alephium.protocol.model.Transaction
import org.alephium.util.AVector

case class BlockTemplate(deps: AVector[Keccak256],
                         target: BigInt,
                         transactions: AVector[Transaction])
