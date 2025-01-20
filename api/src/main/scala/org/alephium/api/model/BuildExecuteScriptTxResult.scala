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

final case class BuildExecuteScriptTxResult(
    fromGroup: Int,
    toGroup: Int,
    unsignedTx: String,
    gasAmount: GasBox,
    gasPrice: GasPrice,
    txId: TransactionId,
    simulationResult: SimulationResult
) extends GasInfo
    with ChainIndexInfo
    with TransactionInfo

object BuildExecuteScriptTxResult {
  def from(
      unsignedTx: UnsignedTransaction,
      simulationResult: SimulationResult
  )(implicit groupConfig: GroupConfig): BuildExecuteScriptTxResult =
    BuildExecuteScriptTxResult(
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
    inputs: AVector[AddressAssetState],
    outputs: AVector[AddressAssetState]
)

object SimulationResult {
  def from(
      unsignedTx: UnsignedTransaction,
      txScriptExecution: TxScriptExecution
  ): Either[String, SimulationResult] = {
    val fixedInputs      = txScriptExecution.fixedInputsPrevOutputs.map(AddressAssetState.from)
    val fixedOutputs     = unsignedTx.fixedOutputs.map(AddressAssetState.from)
    val contractInputs   = txScriptExecution.contractPrevOutputs.map(AddressAssetState.from)
    val generatedOutputs = txScriptExecution.generatedOutputs.map(AddressAssetState.from)
    for {
      inputs  <- AddressAssetState.merge(fixedInputs ++ contractInputs)
      outputs <- AddressAssetState.merge(fixedOutputs ++ generatedOutputs)
    } yield SimulationResult(inputs, outputs)
  }
}
