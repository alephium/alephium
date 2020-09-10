package org.alephium.wallet.api.model

import org.alephium.protocol.model.Address
import org.alephium.util.U64

final case class Transfer(address: Address, amount: U64)

object Transfer {
  final case class Result(txId: String)
}
