package org.alephium.wallet.api.model

import org.alephium.protocol.model.Address
import org.alephium.util.{AVector, U64}

final case class Balances(totalBalance: U64, balances: AVector[Balances.AddressBalance])

object Balances {
  final case class AddressBalance(address: Address, balance: U64)
}
