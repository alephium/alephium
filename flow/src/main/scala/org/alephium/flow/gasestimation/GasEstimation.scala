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

import org.alephium.flow.core.UtxoSelectionAlgo.TxInputWithAsset
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.util._

// Estimate gas based on execution
//   - UnlockScript, including P2PKH, P2MPKH and P2SH
//   - TxScript
object GasEstimation extends StrictLogging {
  def sweepAddress: (Int, Int) => GasBox = estimateWithSameP2PKHInputs _

  def gasForSameP2PKHInputs(numInputs: Int): GasBox = {
    assume(numInputs > 0)
    val firstInputGas = GasSchedule.txInputBaseGas.addUnsafe(GasSchedule.secp256K1UnlockGas)
    firstInputGas.addUnsafe(GasSchedule.txInputBaseGas.mulUnsafe(numInputs - 1))
  }

  def estimateWithSameP2PKHInputs(numInputs: Int, numOutputs: Int): GasBox = {
    val inputGas = gasForSameP2PKHInputs(numInputs)
    estimate(inputGas, numOutputs)
  }

  def estimateWithInputScript(
      lockPair: (LockupScript, UnlockScript),
      numInputs: Int,
      numOutputs: Int,
      assetScriptGasEstimator: AssetScriptGasEstimator
  ): Either[String, GasBox] = {
    val inputs = AVector.fill(numInputs)(lockPair)
    estimate(inputs, numOutputs, assetScriptGasEstimator)
  }

  def estimate(
      lockPairs: AVector[(LockupScript, UnlockScript)],
      numOutputs: Int,
      assetScriptGasEstimator: AssetScriptGasEstimator
  ): Either[String, GasBox] = {
    val inputGas: Either[String, GasBox] =
      lockPairs.foldWithIndexE(GasBox.zero) { case (sum, lockPair, index) =>
        val sameAsPrevious = index > 0 && lockPairs(index - 1) == lockPair
        if (sameAsPrevious) {
          Right(sum.addUnsafe(GasSchedule.txInputBaseGas))
        } else {
          estimateInputGas(lockPair, assetScriptGasEstimator).map(_.addUnsafe(sum))
        }
      }

    inputGas.map { gas => estimate(gas, numOutputs) }
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
      inputWithAssets: AVector[TxInputWithAsset],
      script: StatefulScript,
      txScriptEmulator: TxScriptEmulator
  ): Either[String, GasBox] = {
    if (inputWithAssets.isEmpty) {
      Left("Insufficient funds for gas")
    } else {
      txScriptEmulator.emulate(inputWithAssets, AVector.empty, script).map(_.gasUsed)
    }
  }

  private[gasestimation] def estimateInputGas(
      lockPair: (LockupScript, UnlockScript),
      assetScriptGasEstimator: AssetScriptGasEstimator
  ): Either[String, GasBox] = {
    lockPair._2 match {
      case _: UnlockScript.P2PKH | _: UnlockScript.PoLW =>
        Right(GasSchedule.txInputBaseGas.addUnsafe(GasSchedule.secp256K1UnlockGas))
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
        Right(GasSchedule.txInputBaseGas)
      case UnlockScript.P2PK =>
        lockPair._1 match {
          case lockupScript: LockupScript.P2PK =>
            Right(estimateInputGas(lockupScript))
          case lockupScript =>
            Left(s"Invalid lockup script $lockupScript, expected LockupScript.P2PK")
        }
      case p2hmpk: UnlockScript.P2HMPK =>
        val publicKeys = p2hmpk.publicKeyIndexes.fold(AVector.empty[PublicKeyLike]) {
          (acc, index) =>
            acc :+ p2hmpk.publicKeys(index)
        }
        Right(
          GasSchedule.txInputBaseGas.addUnsafe(
            GasSchedule.p2hmpkUnlockGas(publicKeys)
          )
        )
    }
  }

  private[gasestimation] def estimateInputGas(lockupScript: LockupScript.P2PK): GasBox = {
    lockupScript.publicKey match {
      case _: PublicKeyLike.SecP256K1 =>
        GasSchedule.txInputBaseGas.addUnsafe(GasSchedule.secp256K1UnlockGas)
      case _: PublicKeyLike.SecP256R1 =>
        GasSchedule.txInputBaseGas.addUnsafe(GasSchedule.secp256R1UnlockGas)
      case _: PublicKeyLike.ED25519 =>
        GasSchedule.txInputBaseGas.addUnsafe(GasSchedule.ed25519UnlockGas)
      case _: PublicKeyLike.WebAuthn =>
        // scalastyle:off magic.number
        GasSchedule.txInputBaseGas.addUnsafe(GasSchedule.webauthnUnlockGas(500))
      // scalastyle:on magic.number
    }
  }
}
