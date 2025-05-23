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
import org.alephium.protocol.model.{TransactionId, UnsignedTransaction}
import org.alephium.protocol.vm.{GasBox, GasPrice}
import org.alephium.protocol.vm.StatefulVM.TxScriptExecution
import org.alephium.serde.serialize
import org.alephium.util.{AVector, Hex}

sealed trait BuildExecuteScriptTxResult extends Product with Serializable

@upickle.implicits.key("BuildSimpleExecuteScriptTxResult")
final case class BuildSimpleExecuteScriptTxResult(
    fromGroup: Int,
    toGroup: Int,
    unsignedTx: String,
    gasAmount: GasBox,
    gasPrice: GasPrice,
    txId: TransactionId,
    simulationResult: SimulationResult
) extends BuildExecuteScriptTxResult
    with GasInfo
    with ChainIndexInfo
    with TransactionInfo

object BuildSimpleExecuteScriptTxResult {
  def from(
      unsignedTx: UnsignedTransaction,
      simulationResult: SimulationResult
  )(implicit groupConfig: GroupConfig): BuildSimpleExecuteScriptTxResult =
    BuildSimpleExecuteScriptTxResult(
      unsignedTx.fromGroup.value,
      unsignedTx.toGroup.value,
      Hex.toHexString(serialize(unsignedTx)),
      unsignedTx.gasAmount,
      unsignedTx.gasPrice,
      unsignedTx.id,
      simulationResult
    )
}

final case class SimulationResult(
    contractInputs: AVector[AddressAssetState],
    generatedOutputs: AVector[AddressAssetState]
)

object SimulationResult {
  def from(txScriptExecution: TxScriptExecution): SimulationResult = {
    val contractInputs   = txScriptExecution.contractPrevOutputs.map(AddressAssetState.from)
    val generatedOutputs = txScriptExecution.generatedOutputs.map(AddressAssetState.from)
    SimulationResult(contractInputs, generatedOutputs)
  }
}

@upickle.implicits.key("BuildGrouplessExecuteScriptTxResult")
final case class BuildGrouplessExecuteScriptTxResult(
    fromGroup: Int,
    toGroup: Int,
    unsignedTx: String,
    gasAmount: GasBox,
    gasPrice: GasPrice,
    txId: TransactionId,
    simulationResult: SimulationResult,
    transferTxs: AVector[BuildSimpleTransferTxResult]
) extends BuildExecuteScriptTxResult {
  def executeScriptTx: BuildSimpleExecuteScriptTxResult = {
    BuildSimpleExecuteScriptTxResult(
      fromGroup,
      toGroup,
      unsignedTx,
      gasAmount,
      gasPrice,
      txId,
      simulationResult
    )
  }
}

object BuildGrouplessExecuteScriptTxResult {
  def from(
      executeScriptTx: BuildSimpleExecuteScriptTxResult,
      transferTxs: AVector[BuildSimpleTransferTxResult]
  ): BuildGrouplessExecuteScriptTxResult = {
    BuildGrouplessExecuteScriptTxResult(
      executeScriptTx.fromGroup,
      executeScriptTx.toGroup,
      executeScriptTx.unsignedTx,
      executeScriptTx.gasAmount,
      executeScriptTx.gasPrice,
      executeScriptTx.txId,
      executeScriptTx.simulationResult,
      transferTxs
    )
  }
}
