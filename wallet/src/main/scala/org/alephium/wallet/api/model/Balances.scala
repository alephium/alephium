// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.wallet.api.model

import org.alephium.api.model.Amount
import org.alephium.protocol.model.Address
import org.alephium.util.AVector

final case class Balances(
    totalBalance: Amount,
    totalBalanceHint: Amount.Hint,
    balances: AVector[Balances.AddressBalance]
)

object Balances {
  def from(totalBalance: Amount, balances: AVector[Balances.AddressBalance]): Balances = Balances(
    totalBalance,
    totalBalance.hint,
    balances
  )
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  final case class AddressBalance(
      address: Address.Asset,
      balance: Amount,
      balanceHint: Amount.Hint,
      lockedBalance: Amount,
      lockedBalanceHint: Amount.Hint
  )

  object AddressBalance {
    @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
    def from(
        address: Address.Asset,
        balance: Amount,
        lockedBalance: Amount
    ): AddressBalance = AddressBalance(
      address,
      balance,
      balance.hint,
      lockedBalance,
      lockedBalance.hint
    )
  }
}
