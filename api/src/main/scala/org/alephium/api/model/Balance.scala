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

import org.alephium.util.U256

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class Balance(
    balance: U256,
    lockedBalance: U256,
    utxoNum: Int,
    warning: Option[String] = None
)

object Balance {
  def apply(balance_locked_utxoNum: (U256, U256, Int), utxosLimit: Int): Balance = {
    val warning =
      Option.when(utxosLimit == balance_locked_utxoNum._3)(
        "Result might not include all utxos and is maybe unprecise"
      )

    Balance(
      balance_locked_utxoNum._1,
      balance_locked_utxoNum._2,
      balance_locked_utxoNum._3,
      warning
    )
  }
}
