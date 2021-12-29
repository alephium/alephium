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

package org.alephium.flow.gasestimation

import com.typesafe.scalalogging.StrictLogging

import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.util._

// Estimate gas based on execution
//   - UnlockScript, including P2PKH, P2MPKH and P2SH
//   - TxScript
object GasEstimation extends StrictLogging {
  def sweepAddress: (Int, Int) => GasBox = estimateWithP2PKHInputs _

  def estimateWithP2PKHInputs(numInputs: Int, numOutputs: Int): GasBox = {
    val inputGas = GasSchedule.txInputBaseGas.addUnsafe(GasSchedule.p2pkUnlockGas)
    estimate(inputGas.mulUnsafe(numInputs), numOutputs)
  }

  def estimateWithInputScript(
      unlockScript: UnlockScript,
      numInputs: Int,
      numOutputs: Int,
      assetScriptGasEstimator: AssetScriptGasEstimator
  ): GasBox = {
    val inputs = AVector.fill(numInputs)(unlockScript)
    estimate(inputs, numOutputs, assetScriptGasEstimator)
  }

  def estimate(
      unlockScripts: AVector[UnlockScript],
      numOutputs: Int,
      assetScriptGasEstimator: AssetScriptGasEstimator
  ): GasBox = {
    val inputGas =
      unlockScripts.fold(GasBox.zero)(_ addUnsafe estimateInputGas(_, assetScriptGasEstimator))
    estimate(inputGas, numOutputs)
  }

  def estimate(inputGas: GasBox, numOutputs: Int): GasBox = {
    val gas = GasSchedule.txBaseGas
      .addUnsafe(GasSchedule.txOutputBaseGas.mulUnsafe(numOutputs))
      .addUnsafe(inputGas)

    Math.max(gas, minimalGas)
  }

  def estimate(
      script: StatefulScript,
      txScriptGasEstimator: TxScriptGasEstimator
  ): GasBox = {
    txScriptGasEstimator.estimate(script) match {
      case Left(error) =>
        logger.info(
          s"Estimating gas for TxScript with error $error, fall back to $defaultGasPerInput"
        )
        defaultGasPerInput
      case Right(value) =>
        value
    }
  }

  private[gasestimation] def estimateInputGas(
      unlockScript: UnlockScript,
      assetScriptGasEstimator: AssetScriptGasEstimator
  ): GasBox = {
    unlockScript match {
      case _: UnlockScript.P2PKH =>
        GasSchedule.txInputBaseGas.addUnsafe(GasSchedule.p2pkUnlockGas)
      case p2mpkh: UnlockScript.P2MPKH =>
        GasSchedule.txInputBaseGas.addUnsafe(
          GasSchedule.p2mpkUnlockGas(p2mpkh.indexedPublicKeys.length)
        )
      case p2sh: UnlockScript.P2SH =>
        assetScriptGasEstimator.estimate(p2sh) match {
          case Left(error) =>
            logger.info(
              s"Estimating gas for AssetScript with error $error, fall back to $defaultGasPerInput"
            )
            defaultGasPerInput
          case Right(value) =>
            GasSchedule.txInputBaseGas.addUnsafe(value)
        }
    }
  }
}
