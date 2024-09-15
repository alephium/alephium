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

import akka.util.ByteString

import org.alephium.protocol.Hash
import org.alephium.protocol.model.{Address, TxInput, TxOutput, TxOutputRef}
import org.alephium.serde.serialize
import org.alephium.util.AVector

sealed trait RichInput {
  def hint: Int
  def key: Hash
  def unlockScript: Option[ByteString]
  def attoAlphAmount: Amount
  def address: Address
  def tokens: AVector[Token]
}

@upickle.implicits.key("AssetInput")
final case class RichAssetInput(
    hint: Int,
    key: Hash,
    unlockScript: Option[ByteString],
    attoAlphAmount: Amount,
    address: Address,
    tokens: AVector[Token]
) extends RichInput

@upickle.implicits.key("ContractInput")
final case class RichContractInput(
    hint: Int,
    key: Hash,
    attoAlphAmount: Amount,
    address: Address,
    tokens: AVector[Token]
) extends RichInput {
  def unlockScript: Option[ByteString] = None
}

object RichInput {
  def from(assetInput: TxInput, txOutput: TxOutput): RichAssetInput = {
    RichAssetInput(
      hint = assetInput.outputRef.hint.value,
      key = assetInput.outputRef.key.value,
      unlockScript = Some(serialize(assetInput.unlockScript)),
      attoAlphAmount = Amount(txOutput.amount),
      address = Address.from(txOutput.lockupScript),
      tokens = txOutput.tokens.map(Token.tupled.apply)
    )
  }

  def from(contractOutputRef: TxOutputRef, txOutput: TxOutput): RichContractInput = {
    RichContractInput(
      hint = contractOutputRef.hint.value,
      key = contractOutputRef.key.value,
      attoAlphAmount = Amount(txOutput.amount),
      address = Address.from(txOutput.lockupScript),
      tokens = txOutput.tokens.map(Token.tupled.apply)
    )
  }
}
