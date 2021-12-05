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

package org.alephium.flow.core

import com.typesafe.scalalogging.StrictLogging

import org.alephium.protocol.config.{GroupConfig, NetworkConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.protocol.vm.StatefulVM.TxScriptExecution
import org.alephium.util._

// Gas per input is fixed to GasSchedule.txInputBaseGas
// Gas per output is charged ahead of time, depending on the type of the UnlockScript
object GasEstimation extends StrictLogging {
  def sweepAll: (Int, Int) => GasBox = estimateWithP2PKHOutputs _

  def estimateWithP2PKHOutputs(numInputs: Int, numOutputs: Int): GasBox = {
    val outputGas = GasSchedule.txOutputBaseGas.addUnsafe(GasSchedule.p2pkUnlockGas)
    estimate(numInputs, outputGas.mulUnsafe(numOutputs))
  }

  def estimate(numInputs: Int, outputs: AVector[LockupScript.Asset]): GasBox = {
    val outputGas = outputs.fold(GasBox.zero)(_ addUnsafe estimateOutputGas(_))
    estimate(numInputs, outputGas)
  }

  def estimate(numInputs: Int, outputGas: GasBox): GasBox = {
    val gas = GasSchedule.txBaseGas
      .addUnsafe(GasSchedule.txInputBaseGas.mulUnsafe(numInputs))
      .addUnsafe(outputGas)

    Math.max(gas, minimalGas)
  }

  def estimate(
      script: StatefulScript,
      inputs: AVector[TxInput],
      flow: BlockFlow
  )(implicit networkConfig: NetworkConfig, config: GroupConfig): GasBox = {
    estimateTxScript(script, inputs, flow) match {
      case Left(error) =>
        logger.info(s"Estimating gas for TxScript with error $error, fall back to $maximalGasPerTx")
        maximalGasPerTx
      case Right(value) =>
        value
    }
  }

  private[core] def estimateTxScript(
      script: StatefulScript,
      inputs: AVector[TxInput],
      flow: BlockFlow
  )(implicit networkConfig: NetworkConfig, config: GroupConfig): Either[String, GasBox] = {
    val chainIndexOpt = inputs.headOption.map(input => ChainIndex(input.fromGroup, input.fromGroup))

    def runScript(
        blockEnv: BlockEnv,
        groupView: BlockFlowGroupView[WorldState.Cached],
        preOutputs: Option[AVector[AssetOutput]]
    ): Either[String, TxScriptExecution] = {
      val txTemplate = TransactionTemplate(
        UnsignedTransaction(Some(script), inputs, AVector.empty),
        inputSignatures = AVector.empty,
        scriptSignatures = AVector.empty
      )

      val result = VM.checkCodeSize(maximalGasPerTx, script.bytes).flatMap { remainingGas =>
        StatefulVM.runTxScript(
          groupView.worldState.staging(),
          blockEnv,
          txTemplate,
          preOutputs,
          script,
          remainingGas
        )
      }

      result match {
        case Right(value)       => Right(value)
        case Left(Right(error)) => Left(error.name)
        case Left(Left(error))  => Left(error.name)
      }
    }

    for {
      chainIndex <- chainIndexOpt.toRight("No UTXO found.")
      blockEnv   <- flow.getDryrunBlockEnv(chainIndex).left.map(_.toString())
      groupView  <- flow.getMutableGroupView(chainIndex.from).left.map(_.toString())
      preOutputs <- groupView.getPreOutputs(inputs).left.map(_.toString())
      result     <- runScript(blockEnv, groupView, preOutputs)
    } yield {
      maximalGasPerTx.subUnsafe(result.gasBox)
    }
  }

  private def estimateOutputGas(lockupScript: LockupScript.Asset): GasBox = {
    lockupScript match {
      case _: LockupScript.P2PKH =>
        GasSchedule.txOutputBaseGas.addUnsafe(GasSchedule.p2pkUnlockGas)
      case p2mpkh: LockupScript.P2MPKH =>
        GasSchedule.txOutputBaseGas.addUnsafe(
          GasSchedule.p2mpkUnlockGas(p2mpkh.pkHashes.length, p2mpkh.m)
        )
      case _: LockupScript.P2SH =>
        defaultGasPerOutput // TODO: How to estimate the gas for P2SH script?
    }
  }
}
