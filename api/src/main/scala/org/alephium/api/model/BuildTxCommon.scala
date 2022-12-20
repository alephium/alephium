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

import org.alephium.protocol.model.{BlockHash, TokenId}
import org.alephium.protocol.vm.{GasBox, GasPrice}
import org.alephium.util.{AVector, U256}

trait BuildTxCommon {
  def gasAmount: Option[GasBox]

  def gasPrice: Option[GasPrice]

  def targetBlockHash: Option[BlockHash]
}

object BuildTxCommon {
  def getAlphAndTokenAmounts(
      attoAlphAmount: Option[Amount],
      tokensAmount: Option[AVector[Token]]
  ): Either[String, (Option[U256], AVector[(TokenId, U256)])] = {
    val alphAmountOpt = attoAlphAmount.map(_.value)
    tokensAmount match {
      case None => Right((alphAmountOpt, AVector.empty))
      case Some(tokens) =>
        val amounts = tokens.foldE((alphAmountOpt, Map.empty[TokenId, U256])) {
          case ((Some(alphAmount), tokenList), Token(TokenId.alph, tokenAmount)) =>
            alphAmount
              .add(tokenAmount)
              .toRight("ALPH amount overflow")
              .map(v => (Some(v), tokenList))
          case ((None, tokenList), Token(TokenId.alph, tokenAmount)) =>
            Right((Some(tokenAmount), tokenList))
          case ((alphOpt, tokenList), Token(tokenId, tokenAmount)) =>
            if (tokenList.contains(tokenId)) {
              tokenList(tokenId)
                .add(tokenAmount)
                .toRight(s"Token $tokenId amount overflow")
                .map(v => (alphOpt, tokenList + (tokenId -> v)))
            } else {
              Right((alphOpt, tokenList + (tokenId -> tokenAmount)))
            }
        }
        amounts.map(v => (v._1, AVector.from(v._2)))
    }
  }
}

trait GasInfo {
  def gasAmount: GasBox

  def gasPrice: GasPrice
}
