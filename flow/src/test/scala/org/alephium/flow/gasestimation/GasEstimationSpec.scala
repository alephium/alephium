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

import org.scalacheck.Gen

import org.alephium.protocol.ALPH
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm.GasBox
import org.alephium.util._

class GasEstimationSpec extends AlephiumSpec with TxInputGenerators {
  implicit val groupConfig = new GroupConfig {
    override def groups: Int = 2
  }

  "GasEstimation.estimateGasWithP2PKHOutputs" should "estimate the gas for P2PKH outputs" in {
    GasEstimation.estimateWithP2PKHInputs(0, 0) is minimalGas
    GasEstimation.estimateWithP2PKHInputs(1, 0) is minimalGas
    GasEstimation.estimateWithP2PKHInputs(0, 1) is minimalGas
    GasEstimation.estimateWithP2PKHInputs(1, 1) is minimalGas
    GasEstimation.estimateWithP2PKHInputs(2, 3) is GasBox.unsafe(22620)
    GasEstimation.estimateWithP2PKHInputs(3, 3) is GasBox.unsafe(26680)
    GasEstimation.estimateWithP2PKHInputs(5, 10) is GasBox.unsafe(66300)
  }

  "GasEstimation.sweepAll" should "behave the same as GasEstimation.estimateGasWithP2PKHOutputs" in {
    val inputNumGen  = Gen.choose(0, ALPH.MaxTxInputNum)
    val outputNumGen = Gen.choose(0, ALPH.MaxTxOutputNum)

    forAll(inputNumGen, outputNumGen) { case (inputNum, outputNum) =>
      val sweepAllGas = GasEstimation.sweepAll(inputNum, outputNum)
      sweepAllGas is GasEstimation.estimateWithP2PKHInputs(inputNum, outputNum)
    }
  }

  "GasEstimation.estimateGas" should "take output lockup script into consideration" in {
    val groupIndex = groupIndexGen.sample.value
    val p2pkhUnlockScripts =
      Gen.listOfN(3, p2pkhUnlockGen(groupIndex)).map(AVector.from).sample.value

    GasEstimation.estimate(p2pkhUnlockScripts, 2, AssetScriptGasEstimator.Mock) is GasBox.unsafe(
      22180
    )

    val p2mphkUnlockScript1 = p2mpkhUnlockGen(3, 2, groupIndex).sample.value
    GasEstimation.estimate(
      p2mphkUnlockScript1 +: p2pkhUnlockScripts,
      2,
      AssetScriptGasEstimator.Mock
    ) is GasBox.unsafe(28300)

    val p2mphkUnlockScript2 = p2mpkhUnlockGen(5, 3, groupIndex).sample.value
    GasEstimation.estimate(
      p2mphkUnlockScript2 +: p2pkhUnlockScripts,
      2,
      AssetScriptGasEstimator.Mock
    ) is GasBox.unsafe(30360)

    GasEstimation.estimate(
      p2mphkUnlockScript1 +: p2mphkUnlockScript2 +: p2pkhUnlockScripts,
      2,
      AssetScriptGasEstimator.Mock
    ) is GasBox.unsafe(36480)
  }
}
