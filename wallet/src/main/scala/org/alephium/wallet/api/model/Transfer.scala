package org.alephium.wallet.api.model

import org.alephium.protocol.model.Address

final case class Transfer(address: Address, amount: Long)

object Transfer {
  final case class Result(txId: String)
}
