package org.alephium.flow.model

import org.alephium.crypto.Keccak256
import org.alephium.protocol.model.Transaction

case class BlockTemplate(deps: Seq[Keccak256], target: BigInt, transactions: Seq[Transaction])
