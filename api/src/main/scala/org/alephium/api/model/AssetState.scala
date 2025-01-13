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
import org.alephium.protocol.model.{minimalAlphInContract, ContractId, ContractOutput, TokenId}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{AVector, U256}

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class AssetState(attoAlphAmount: U256, tokens: Option[AVector[Token]] = None) {
  lazy val flatTokens: AVector[Token] = tokens.getOrElse(AVector.empty)

  def toContractOutput(contractId: ContractId): ContractOutput = {
    ContractOutput(
      attoAlphAmount,
      LockupScript.p2c(contractId),
      flatTokens.map(token => (token.id, token.amount))
    )
  }
}

object AssetState {
  def from(attoAlphAmount: U256, tokens: AVector[Token]): AssetState = {
    AssetState(attoAlphAmount, Some(tokens))
  }

  def forTesting(tokens: AVector[(TokenId, U256)]): AssetState = {
    val attoAlphAmount =
      tokens.find(_._1 == TokenId.alph).map(_._2).getOrElse(minimalAlphInContract)
    val remains = tokens.filter(_._1 != TokenId.alph).map(Token.tupled)
    AssetState(attoAlphAmount, Option.when(remains.nonEmpty)(remains))
  }

  def from(output: model.TxOutput): AssetState = {
    AssetState.from(output.amount, output.tokens.map(pair => Token(pair._1, pair._2)))
  }
}
