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

import org.scalacheck.Gen

import org.alephium.protocol.ALPH
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm.GasBox
import org.alephium.util._

class GasEstimationSpec extends AlephiumSpec with LockupScriptGenerators {
  implicit val groupConfig = new GroupConfig {
    override def groups: Int = 2
  }

  "GasEstimation.estimateGasWithP2PKHOutputs" should "estimate the gas for P2PKH outputs" in {
    GasEstimation.estimateGasWithP2PKHOutputs(0, 0) is minimalGas
    GasEstimation.estimateGasWithP2PKHOutputs(1, 0) is minimalGas
    GasEstimation.estimateGasWithP2PKHOutputs(0, 1) is minimalGas
    GasEstimation.estimateGasWithP2PKHOutputs(1, 1) is minimalGas
    GasEstimation.estimateGasWithP2PKHOutputs(2, 3) is GasBox.unsafe(24680)
    GasEstimation.estimateGasWithP2PKHOutputs(3, 3) is GasBox.unsafe(26680)
    GasEstimation.estimateGasWithP2PKHOutputs(5, 10) is GasBox.unsafe(76600)
  }

  "GasEstimation.sweepAll" should "behave the same as GasEstimation.estimateGasWithP2PKHOutputs" in {
    val inputNumGen  = Gen.choose(0, ALPH.MaxTxInputNum)
    val outputNumGen = Gen.choose(0, ALPH.MaxTxOutputNum)

    forAll(inputNumGen, outputNumGen) { case (inputNum, outputNum) =>
      val sweepAllGas = GasEstimation.sweepAll(inputNum, outputNum)
      sweepAllGas is GasEstimation.estimateGasWithP2PKHOutputs(inputNum, outputNum)
    }
  }

  "GasEstimation.estimateGas" should "take output lockup script into consideration" in {
    val groupIndex = groupIndexGen.sample.value
    val p2pkhLockupScripts =
      Gen.listOfN(3, p2pkhLockupGen(groupIndex)).map(AVector.from).sample.value

    GasEstimation.estimateGas(2, p2pkhLockupScripts) is GasBox.unsafe(24680)

    val p2mphkLockupScript1 = p2mpkhLockupGen(3, 2, groupIndex).sample.value
    GasEstimation.estimateGas(
      2,
      p2mphkLockupScript1 +: p2pkhLockupScripts
    ) is GasBox.unsafe(33420)

    val p2mphkLockupScript2 = p2mpkhLockupGen(5, 3, groupIndex).sample.value
    GasEstimation.estimateGas(
      2,
      p2mphkLockupScript2 +: p2pkhLockupScripts
    ) is GasBox.unsafe(35540)

    GasEstimation.estimateGas(
      2,
      p2mphkLockupScript1 +: p2mphkLockupScript2 +: p2pkhLockupScripts
    ) is GasBox.unsafe(44280)
  }
}
