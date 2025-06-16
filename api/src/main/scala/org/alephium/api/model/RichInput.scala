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
import org.alephium.protocol.model._
import org.alephium.serde.serialize
import org.alephium.util.AVector

sealed trait RichInput {
  def hint: Int
  def key: Hash
  def attoAlphAmount: Amount
  def address: Address
  def tokens: AVector[Token]
  def outputRefTxId: TransactionId
}

@upickle.implicits.key("AssetInput")
final case class RichAssetInput(
    hint: Int,
    key: Hash,
    unlockScript: ByteString,
    attoAlphAmount: Amount,
    address: Address.Asset,
    tokens: AVector[Token],
    outputRefTxId: TransactionId
) extends RichInput

@upickle.implicits.key("ContractInput")
final case class RichContractInput(
    hint: Int,
    key: Hash,
    attoAlphAmount: Amount,
    address: Address.Contract,
    tokens: AVector[Token],
    outputRefTxId: TransactionId
) extends RichInput

object RichInput {
  def from(
      assetInput: TxInput,
      txOutput: AssetOutput,
      outputRefTxId: TransactionId
  ): RichAssetInput = {
    RichAssetInput(
      hint = assetInput.outputRef.hint.value,
      key = assetInput.outputRef.key.value,
      unlockScript = serialize(assetInput.unlockScript),
      attoAlphAmount = Amount(txOutput.amount),
      address = Address.Asset(txOutput.lockupScript),
      tokens = txOutput.tokens.map(Token.tupled.apply),
      outputRefTxId = outputRefTxId
    )
  }

  def from(
      contractOutputRef: TxOutputRef,
      txOutput: ContractOutput,
      outputRefTxId: TransactionId
  ): RichContractInput = {
    RichContractInput(
      hint = contractOutputRef.hint.value,
      key = contractOutputRef.key.value,
      attoAlphAmount = Amount(txOutput.amount),
      address = Address.Contract(txOutput.lockupScript),
      tokens = txOutput.tokens.map(Token.tupled.apply),
      outputRefTxId = outputRefTxId
    )
  }
}
