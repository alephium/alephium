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

import org.alephium.api.{badRequest, Try}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{TransactionId, UnsignedTransaction}
import org.alephium.protocol.vm.{GasBox, GasPrice}
import org.alephium.serde.serialize
import org.alephium.util.{AVector, Hex}

sealed trait BuildTransferTxResult extends Product with Serializable

@upickle.implicits.key("BuildSimpleTransferTxResult")
final case class BuildSimpleTransferTxResult(
    unsignedTx: String,
    gasAmount: GasBox,
    gasPrice: GasPrice,
    txId: TransactionId,
    fromGroup: Int,
    toGroup: Int
) extends BuildTransferTxResult
    with GasInfo
    with ChainIndexInfo
    with TransactionInfo

object BuildSimpleTransferTxResult {
  def from(
      unsignedTx: UnsignedTransaction
  )(implicit groupConfig: GroupConfig): BuildSimpleTransferTxResult =
    BuildSimpleTransferTxResult(
      Hex.toHexString(serialize(unsignedTx)),
      unsignedTx.gasAmount,
      unsignedTx.gasPrice,
      unsignedTx.id,
      unsignedTx.fromGroup.value,
      unsignedTx.toGroup.value
    )
}

@upickle.implicits.key("BuildGrouplessTransferTxResult")
final case class BuildGrouplessTransferTxResult(
    transferTxs: AVector[BuildSimpleTransferTxResult],
    transferTx: BuildSimpleTransferTxResult
) extends BuildTransferTxResult
object BuildGrouplessTransferTxResult {
  def from(
      transferTxs: AVector[BuildSimpleTransferTxResult]
  ): Try[BuildGrouplessTransferTxResult] =
    if (transferTxs.isEmpty) {
      Left(badRequest("transferTxs is empty"))
    } else {
      Right(BuildGrouplessTransferTxResult(transferTxs.init, transferTxs.last))
    }
}
