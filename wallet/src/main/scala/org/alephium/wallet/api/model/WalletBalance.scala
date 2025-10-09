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

import org.alephium.api.model.{Amount, Balance}
import org.alephium.protocol.model.Address
import org.alephium.util.AVector

final case class WalletBalance(
    totalBalance: Amount,
    totalBalanceHint: Amount.Hint,
    balances: AVector[WalletBalance.WalletAddressBalance]
)

object WalletBalance {
  def from(
      totalBalance: Amount,
      balances: AVector[WalletBalance.WalletAddressBalance]
  ): WalletBalance = WalletBalance(
    totalBalance,
    totalBalance.hint,
    balances
  )
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  final case class WalletAddressBalance(
      address: Address.Asset,
      balance: Balance
  )

  object WalletAddressBalance {
    @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
    def from(
        address: Address.Asset,
        balance: Balance
    ): WalletAddressBalance = WalletAddressBalance(
      address,
      balance
    )
  }
}
