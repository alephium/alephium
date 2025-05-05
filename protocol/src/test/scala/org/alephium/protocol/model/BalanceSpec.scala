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

package org.alephium.protocol.model

import org.alephium.protocol.ALPH
import org.alephium.util.{AlephiumSpec, AVector, U256}

class BalanceSpec extends AlephiumSpec {
  it should "merge balance" in {
    val tokenId0 = TokenId.random
    val tokenId1 = TokenId.random
    val balance0 = Balance(U256.One, U256.Zero, AVector(tokenId0 -> U256.One), AVector.empty, 1)
    balance0.merge(Balance.zero) isE balance0
    Balance.zero.merge(balance0) isE balance0

    val balance1 = Balance(U256.One, U256.One, AVector(tokenId1 -> U256.Two), AVector.empty, 1)
    val balance2 = balance0.merge(balance1).rightValue
    balance2 is Balance(
      U256.Two,
      U256.One,
      AVector(tokenId0 -> U256.One, tokenId1 -> U256.Two),
      AVector.empty,
      2
    )

    val balance3 =
      Balance(U256.One, U256.Zero, AVector(tokenId0 -> U256.One), AVector(tokenId0 -> U256.One), 2)
    val balance4 = Balance(
      U256.One,
      U256.One,
      AVector(tokenId0 -> U256.One, tokenId1 -> U256.Two),
      AVector(tokenId0 -> U256.One, tokenId1 -> U256.Two),
      2
    )
    val balance5 = balance2.merge(balance3).rightValue.merge(balance4).rightValue
    balance5 is Balance(
      U256.unsafe(4),
      U256.Two,
      AVector(tokenId0 -> U256.unsafe(3), tokenId1 -> U256.unsafe(4)),
      AVector(tokenId0 -> U256.Two, tokenId1       -> U256.Two),
      6
    )

    balance5
      .merge(Balance.zero.copy(totalAlph = ALPH.MaxALPHValue))
      .leftValue is "ALPH amount overflow"
    balance5
      .merge(Balance.zero.copy(totalTokens = AVector(tokenId0 -> U256.MaxValue)))
      .leftValue is "Token amount overflow"
  }
}
