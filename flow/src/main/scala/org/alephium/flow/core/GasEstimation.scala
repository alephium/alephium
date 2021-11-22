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

import org.alephium.protocol.model._
import org.alephium.protocol.vm.{GasBox, GasSchedule, LockupScript}
import org.alephium.util._

// Gas per input is fixed to GasSchedule.txInputBaseGas
// Gas per output is charged ahead of time, depending on the type of the UnlockScript
object GasEstimation {
  def sweepAll: (Int, Int) => GasBox = estimateGasWithP2PKHOutputs _

  def estimateGasWithP2PKHOutputs(numInputs: Int, numOutputs: Int): GasBox = {
    val outputGas = GasSchedule.txOutputBaseGas.addUnsafe(GasSchedule.p2pkUnlockGas)
    estimateGas(numInputs, outputGas.mulUnsafe(numOutputs))
  }

  def estimateGas(numInputs: Int, outputs: AVector[LockupScript.Asset]): GasBox = {
    val outputGas = outputs.fold(GasBox.zero)(_ addUnsafe estimateOutputGas(_))
    estimateGas(numInputs, outputGas)
  }

  def estimateGas(numInputs: Int, outputGas: GasBox): GasBox = {
    val gas = GasSchedule.txBaseGas
      .addUnsafe(GasSchedule.txInputBaseGas.mulUnsafe(numInputs))
      .addUnsafe(outputGas)

    Math.max(gas, minimalGas)
  }

  def estimateContractCreationGas(): GasBox = {
    // estimate the cost of
    // 1. execution of contract creation
    // 2. contract code size
    // 3. contract state size
    ???
  }

  def estimateTxScriptGas(): GasBox = {
    // based on code size?
    ???
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
