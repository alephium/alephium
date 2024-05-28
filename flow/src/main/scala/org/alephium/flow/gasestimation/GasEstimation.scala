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

  def gasForP2PKHInputs(numInputs: Int): GasBox = {
    val inputGas = GasSchedule.txInputBaseGas.addUnsafe(GasSchedule.p2pkUnlockGas)
    inputGas.mulUnsafe(numInputs)
  }

  def estimateWithP2PKHInputs(numInputs: Int, numOutputs: Int): GasBox = {
    val inputGas = gasForP2PKHInputs(numInputs)
    estimate(inputGas, numOutputs)
  }

  def estimateWithInputScript(
      unlockScript: UnlockScript,
      numInputs: Int,
      numOutputs: Int,
      assetScriptGasEstimator: AssetScriptGasEstimator
  ): Either[String, GasBox] = {
    val inputs = AVector.fill(numInputs)(unlockScript)
    estimate(inputs, numOutputs, assetScriptGasEstimator)
  }

  def estimate(
      unlockScripts: AVector[UnlockScript],
      numOutputs: Int,
      assetScriptGasEstimator: AssetScriptGasEstimator
  ): Either[String, GasBox] = {
    val inputGas: Either[String, (GasBox, Option[GasBox])] =
      unlockScripts.foldE((GasBox.zero, None: Option[GasBox])) {
        case ((sum, previousGas), unlock) =>
          estimateInputGas(unlock, previousGas, assetScriptGasEstimator).map { gas =>
            (sum.addUnsafe(gas), Some(gas))
          }
      }

    inputGas.map { case (gas, _) => estimate(gas, numOutputs) }
  }

  def estimate(inputGas: GasBox, numOutputs: Int): GasBox = {
    val gas = rawEstimate(inputGas, numOutputs)

    Math.max(gas, minimalGas)
  }

  def rawEstimate(inputGas: GasBox, numOutputs: Int): GasBox = {
    GasSchedule.txBaseGas
      .addUnsafe(GasSchedule.txOutputBaseGas.mulUnsafe(numOutputs))
      .addUnsafe(inputGas)
  }

  def estimate(
      inputs: AVector[TxInput],
      script: StatefulScript,
      txScriptGasEstimator: TxScriptGasEstimator
  ): Either[String, GasBox] = {
    txScriptGasEstimator.estimate(inputs, script)
  }

  private[gasestimation] def estimateInputGas(
      unlockScript: UnlockScript,
      previousGas: Option[GasBox],
      assetScriptGasEstimator: AssetScriptGasEstimator
  ): Either[String, GasBox] = {
    unlockScript match {
      case _: UnlockScript.P2PKH | _: UnlockScript.PoLW =>
        Right(GasSchedule.txInputBaseGas.addUnsafe(GasSchedule.p2pkUnlockGas))
      case p2mpkh: UnlockScript.P2MPKH =>
        Right(
          GasSchedule.txInputBaseGas.addUnsafe(
            GasSchedule.p2mpkUnlockGas(p2mpkh.indexedPublicKeys.length)
          )
        )
      case p2sh: UnlockScript.P2SH =>
        assetScriptGasEstimator
          .estimate(p2sh)
          .map(GasSchedule.txInputBaseGas.addUnsafe(_))
      case UnlockScript.SameAsPrevious =>
        previousGas.toRight("Error estimating gas for SameAsPrevious unlock script")
    }
  }
}
