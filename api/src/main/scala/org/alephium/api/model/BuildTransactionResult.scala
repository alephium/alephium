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

import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Address, ContractId, TransactionId, UnsignedTransaction}
import org.alephium.protocol.vm.{GasBox, GasPrice}
import org.alephium.serde.serialize
import org.alephium.util.Hex

sealed trait BuildTransactionResult extends GasInfo with ChainIndexInfo

object BuildTransactionResult {
  @upickle.implicits.key("Transfer")
  final case class Transfer(
      unsignedTx: String,
      gasAmount: GasBox,
      gasPrice: GasPrice,
      txId: TransactionId,
      fromGroup: Int,
      toGroup: Int
  ) extends BuildTransactionResult

  object Transfer {
    def from(
        unsignedTx: UnsignedTransaction
    )(implicit groupConfig: GroupConfig): Transfer =
      Transfer(
        Hex.toHexString(serialize(unsignedTx)),
        unsignedTx.gasAmount,
        unsignedTx.gasPrice,
        unsignedTx.id,
        unsignedTx.fromGroup.value,
        unsignedTx.toGroup.value
      )
  }

  @upickle.implicits.key("ExecuteScript")
  final case class ExecuteScript(
      fromGroup: Int,
      toGroup: Int,
      unsignedTx: String,
      gasAmount: GasBox,
      gasPrice: GasPrice,
      txId: TransactionId
  ) extends BuildTransactionResult

  object ExecuteScript {
    def from(
        unsignedTx: UnsignedTransaction
    )(implicit groupConfig: GroupConfig): ExecuteScript =
      ExecuteScript(
        unsignedTx.fromGroup.value,
        unsignedTx.toGroup.value,
        Hex.toHexString(serialize(unsignedTx)),
        unsignedTx.gasAmount,
        unsignedTx.gasPrice,
        unsignedTx.id
      )
  }

  @upickle.implicits.key("DeployContract")
  final case class DeployContract(
      fromGroup: Int,
      toGroup: Int,
      unsignedTx: String,
      gasAmount: GasBox,
      gasPrice: GasPrice,
      txId: TransactionId,
      contractAddress: Address.Contract
  ) extends BuildTransactionResult

  object DeployContract {
    def from(
        unsignedTx: UnsignedTransaction
    )(implicit groupConfig: GroupConfig): BuildTransactionResult.DeployContract = {
      val contractId =
        ContractId.from(unsignedTx.id, unsignedTx.fixedOutputs.length, unsignedTx.fromGroup)
      BuildTransactionResult.DeployContract(
        unsignedTx.fromGroup.value,
        unsignedTx.toGroup.value,
        Hex.toHexString(serialize(unsignedTx)),
        unsignedTx.gasAmount,
        unsignedTx.gasPrice,
        unsignedTx.id,
        Address.contract(contractId)
      )
    }
  }
}
