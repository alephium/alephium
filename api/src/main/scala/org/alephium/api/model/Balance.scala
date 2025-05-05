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

package org.alephium.api.model

import org.alephium.protocol.model
import org.alephium.util.AVector
import org.alephium.util.U256

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class Balance(
    balance: Amount,
    balanceHint: Amount.Hint,
    lockedBalance: Amount,
    lockedBalanceHint: Amount.Hint,
    tokenBalances: Option[AVector[Token]] = None,
    lockedTokenBalances: Option[AVector[Token]] = None,
    utxoNum: Int
)

object Balance {
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def from(
      balance: Amount,
      lockedBalance: Amount,
      tokenBalances: Option[AVector[Token]],
      lockedTokenBalances: Option[AVector[Token]],
      utxoNum: Int
  ): Balance = Balance(
    balance,
    balance.hint,
    lockedBalance,
    lockedBalance.hint,
    tokenBalances,
    lockedTokenBalances,
    utxoNum
  )

  def from(balance: model.Balance): Balance = {
    Balance.from(
      Amount(balance.totalAlph),
      Amount(balance.lockedAlph),
      getTokenBalances(balance.totalTokens),
      getTokenBalances(balance.lockedTokens),
      balance.utxosNum
    )
  }

  private def getTokenBalances(balances: AVector[(model.TokenId, U256)]): Option[AVector[Token]] = {
    val tokenBalances = balances.map(tokenBalance => Token(tokenBalance._1, tokenBalance._2))
    Option.when(tokenBalances.nonEmpty)(tokenBalances)
  }
}
