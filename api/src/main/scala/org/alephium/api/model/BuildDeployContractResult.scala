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
import org.alephium.util.{AVector, Hex}

sealed trait BuildDeployContractTxResult extends Product with Serializable {
  def fromGroup: Int
  def toGroup: Int
  def unsignedTx: String
  def gasAmount: GasBox
  def gasPrice: GasPrice
  def txId: TransactionId
  def contractAddress: Address.Contract
}

@upickle.implicits.key("BuildSimpleDeployContractTxResult")
final case class BuildSimpleDeployContractTxResult(
    fromGroup: Int,
    toGroup: Int,
    unsignedTx: String,
    gasAmount: GasBox,
    gasPrice: GasPrice,
    txId: TransactionId,
    contractAddress: Address.Contract
) extends BuildDeployContractTxResult
    with GasInfo
    with ChainIndexInfo
    with TransactionInfo

object BuildSimpleDeployContractTxResult {
  def from(
      unsignedTx: UnsignedTransaction
  )(implicit groupConfig: GroupConfig): BuildSimpleDeployContractTxResult = {
    val contractId =
      ContractId.from(unsignedTx.id, unsignedTx.fixedOutputs.length, unsignedTx.fromGroup)
    BuildSimpleDeployContractTxResult(
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

@upickle.implicits.key("BuildGrouplessDeployContractTxResult")
final case class BuildGrouplessDeployContractTxResult(
    fromGroup: Int,
    toGroup: Int,
    unsignedTx: String,
    gasAmount: GasBox,
    gasPrice: GasPrice,
    txId: TransactionId,
    contractAddress: Address.Contract,
    fundingTxs: Option[AVector[BuildSimpleTransferTxResult]]
) extends BuildDeployContractTxResult

object BuildGrouplessDeployContractTxResult {
  def from(
      deployContractTx: BuildSimpleDeployContractTxResult,
      fundingTxs: AVector[BuildSimpleTransferTxResult]
  ): BuildGrouplessDeployContractTxResult = {
    BuildGrouplessDeployContractTxResult(
      deployContractTx.fromGroup,
      deployContractTx.toGroup,
      deployContractTx.unsignedTx,
      deployContractTx.gasAmount,
      deployContractTx.gasPrice,
      deployContractTx.txId,
      deployContractTx.contractAddress,
      Option.when(fundingTxs.nonEmpty)(fundingTxs)
    )
  }
}
