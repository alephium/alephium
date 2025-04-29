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
import org.alephium.protocol.model
import org.alephium.protocol.model.{Address, TransactionId, TxOutput}
import org.alephium.util.{AVector, TimeStamp}

sealed trait Output {
  def hint: Int
  def key: Hash
  def attoAlphAmount: Amount
  def address: Address
  def tokens: AVector[Token]
  def toProtocol(): model.TxOutput
}

object Output {
  def from(output: TxOutput, txId: TransactionId, index: Int): Output = {
    output match {
      case o: model.AssetOutput =>
        AssetOutput(
          o.hint.value,
          model.TxOutputRef.key(txId, index).value,
          Amount(o.amount),
          Address.Asset(o.lockupScript),
          o.tokens.map(Token.tupled),
          o.lockTime,
          o.additionalData
        )
      case o: model.ContractOutput =>
        ContractOutput(
          o.hint.value,
          model.TxOutputRef.key(txId, index).value,
          Amount(o.amount),
          Address.Contract(o.lockupScript),
          o.tokens.map(Token.tupled)
        )
    }
  }

  def fromGeneratedOutputs(
      unsignedTx: model.UnsignedTransaction,
      generatedOutputs: AVector[TxOutput]
  ): AVector[Output] = {
    val fixedOutputsLength = unsignedTx.fixedOutputs.length
    generatedOutputs.mapWithIndex { case (output, index) =>
      Output.from(output, unsignedTx.id, fixedOutputsLength + index)
    }
  }
}

@upickle.implicits.key("AssetOutput")
final case class AssetOutput(
    hint: Int,
    key: Hash,
    attoAlphAmount: Amount,
    address: Address.Asset,
    tokens: AVector[Token],
    lockTime: TimeStamp,
    message: ByteString
) extends Output {
  def toProtocol(): model.AssetOutput = {
    model.AssetOutput(
      attoAlphAmount.value,
      address.lockupScript,
      lockTime,
      tokens.map { token => (token.id, token.amount) },
      message
    )
  }
}

@upickle.implicits.key("ContractOutput")
final case class ContractOutput(
    hint: Int,
    key: Hash,
    attoAlphAmount: Amount,
    address: Address.Contract,
    tokens: AVector[Token]
) extends Output {
  def toProtocol(): model.ContractOutput = {
    model.ContractOutput(
      attoAlphAmount.value,
      address.lockupScript,
      tokens.map(token => (token.id, token.amount))
    )
  }
}

final case class FixedAssetOutput(
    hint: Int,
    key: Hash,
    attoAlphAmount: Amount,
    address: Address.Asset,
    tokens: AVector[Token],
    lockTime: TimeStamp,
    message: ByteString
) {
  def toProtocol(): model.AssetOutput = {
    model.AssetOutput(
      attoAlphAmount.value,
      address.lockupScript,
      lockTime,
      tokens.map { token => (token.id, token.amount) },
      message
    )
  }

  def upCast(): AssetOutput = {
    AssetOutput(hint, key, attoAlphAmount, address, tokens, lockTime, message)
  }
}

object FixedAssetOutput {
  def fromProtocol(
      assetOutput: model.AssetOutput,
      txId: TransactionId,
      index: Int
  ): FixedAssetOutput = {
    FixedAssetOutput(
      assetOutput.hint.value,
      model.TxOutputRef.key(txId, index).value,
      Amount(assetOutput.amount),
      Address.Asset(assetOutput.lockupScript),
      assetOutput.tokens.map(Token.tupled),
      assetOutput.lockTime,
      assetOutput.additionalData
    )
  }
}
