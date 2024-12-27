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

import scala.collection.mutable

import org.alephium.protocol.ALPH
import org.alephium.util.{AVector, U256}

final case class Balance(
    totalAlph: U256,
    lockedAlph: U256,
    totalTokens: AVector[(TokenId, U256)],
    lockedTokens: AVector[(TokenId, U256)],
    utxosNum: Int
) {
  private def mergeTokens(
      tokens0: AVector[(TokenId, U256)],
      tokens1: AVector[(TokenId, U256)]
  ) = {
    if (tokens0.isEmpty) {
      Right(tokens1)
    } else if (tokens1.isEmpty) {
      Right(tokens0)
    } else {
      val allTokens = mutable.LinkedHashMap.from(tokens0.iterator)
      tokens1
        .foreachE { case (tokenId, amount) =>
          allTokens.get(tokenId) match {
            case Some(currentAmount) =>
              currentAmount
                .add(amount)
                .map(total => allTokens.update(tokenId, total))
                .toRight("Token amount overflow")
            case None =>
              Right(allTokens.update(tokenId, amount))
          }
        }
        .map(_ => AVector.from(allTokens))
    }
  }

  def mergeAlph(balance0: U256, balance1: U256): Either[String, U256] = {
    balance0.add(balance1).toRight("ALPH amount overflow").flatMap { sum =>
      if (sum > ALPH.MaxALPHValue) Left("ALPH amount overflow") else Right(sum)
    }
  }

  def merge(right: Balance): Either[String, Balance] = {
    for {
      newTotalAlph    <- mergeAlph(totalAlph, right.totalAlph)
      newLockedAlph   <- mergeAlph(lockedAlph, right.lockedAlph)
      newTotalTokens  <- mergeTokens(totalTokens, right.totalTokens)
      newLockedTokens <- mergeTokens(lockedTokens, right.lockedTokens)
    } yield Balance(
      newTotalAlph,
      newLockedAlph,
      newTotalTokens,
      newLockedTokens,
      utxosNum + right.utxosNum
    )
  }
}

object Balance {
  lazy val zero: Balance =
    Balance(U256.Zero, U256.Zero, AVector.empty, AVector.empty, 0)
}
