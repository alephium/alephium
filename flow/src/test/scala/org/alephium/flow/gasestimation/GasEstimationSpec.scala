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

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.protocol.ALPH
import org.alephium.protocol.model._
import org.alephium.protocol.model.UnsignedTransaction.TxOutputInfo
import org.alephium.protocol.vm.{GasBox, LockupScript, UnlockScript, Val}
import org.alephium.protocol.vm.lang.Compiler
import org.alephium.util._

class GasEstimationSpec extends AlephiumFlowSpec with TxInputGenerators {

  "GasEstimation.estimateWithP2PKHInputs" should "estimate the gas for P2PKH inputs" in {
    GasEstimation.estimateWithP2PKHInputs(0, 0) is minimalGas
    GasEstimation.estimateWithP2PKHInputs(1, 0) is minimalGas
    GasEstimation.estimateWithP2PKHInputs(0, 1) is minimalGas
    GasEstimation.estimateWithP2PKHInputs(1, 1) is minimalGas
    GasEstimation.estimateWithP2PKHInputs(2, 3) is GasBox.unsafe(22620)
    GasEstimation.estimateWithP2PKHInputs(3, 3) is GasBox.unsafe(26680)
    GasEstimation.estimateWithP2PKHInputs(5, 10) is GasBox.unsafe(66300)
  }

  "GasEstimation.sweepAddress" should "behave the same as GasEstimation.estimateWithP2PKHInputs" in {
    val inputNumGen  = Gen.choose(0, ALPH.MaxTxInputNum)
    val outputNumGen = Gen.choose(0, ALPH.MaxTxOutputNum)

    forAll(inputNumGen, outputNumGen) { case (inputNum, outputNum) =>
      val sweepAddressGas = GasEstimation.sweepAddress(inputNum, outputNum)
      sweepAddressGas is GasEstimation.estimateWithP2PKHInputs(inputNum, outputNum)
    }
  }

  "GasEstimation.estimate" should "take input unlock script into consideration" in {
    val groupIndex = groupIndexGen.sample.value
    val p2pkhUnlockScripts =
      Gen.listOfN(3, p2pkhUnlockGen(groupIndex)).map(AVector.from).sample.value

    GasEstimation
      .estimate(p2pkhUnlockScripts, 2, AssetScriptGasEstimator.Mock)
      .rightValue is GasBox.unsafe(22180)

    val p2mphkUnlockScript1 = p2mpkhUnlockGen(3, 2, groupIndex).sample.value
    GasEstimation
      .estimate(
        p2mphkUnlockScript1 +: p2pkhUnlockScripts,
        2,
        AssetScriptGasEstimator.Mock
      )
      .rightValue is GasBox.unsafe(28300)

    val p2mphkUnlockScript2 = p2mpkhUnlockGen(5, 3, groupIndex).sample.value
    GasEstimation
      .estimate(
        p2mphkUnlockScript2 +: p2pkhUnlockScripts,
        2,
        AssetScriptGasEstimator.Mock
      )
      .rightValue is GasBox.unsafe(30360)

    GasEstimation
      .estimate(
        p2mphkUnlockScript1 +: p2mphkUnlockScript2 +: p2pkhUnlockScripts,
        2,
        AssetScriptGasEstimator.Mock
      )
      .rightValue is GasBox.unsafe(36480)
  }

  "GasEstimation.estimateWithInputScript" should "take input unlock script into consideration" in {
    val groupIndex = groupIndexGen.sample.value

    info("P2PKH")

    {
      val script        = p2pkhUnlockGen(groupIndex).sample.value
      val mockEstimator = AssetScriptGasEstimator.Mock

      GasEstimation.estimateWithInputScript(script, 1, 2, mockEstimator).rightValue is GasBox
        .unsafe(20000)
      GasEstimation.estimateWithInputScript(script, 4, 2, mockEstimator).rightValue is GasBox
        .unsafe(26240)
      GasEstimation.estimateWithInputScript(script, 10, 2, mockEstimator).rightValue is GasBox
        .unsafe(50600)
    }

    info("P2MPKH")

    {
      val script        = p2mpkhUnlockGen(3, 2, groupIndex).sample.value
      val mockEstimator = AssetScriptGasEstimator.Mock

      GasEstimation.estimateWithInputScript(script, 1, 2, mockEstimator).rightValue is GasBox
        .unsafe(20000)
      GasEstimation.estimateWithInputScript(script, 4, 2, mockEstimator).rightValue is GasBox
        .unsafe(34480)
      GasEstimation.estimateWithInputScript(script, 10, 2, mockEstimator).rightValue is GasBox
        .unsafe(71200)
    }

    info("P2SH, no signature required")

    {
      def p2shNoSignature(i: Int) = {
        val raw =
          s"""
             |AssetScript Foo {
             |  pub fn bar(a: U256, b: U256) -> () {
             |    let mut c = 0u
             |    let mut d = 0u
             |    let mut e = 0u
             |    let mut f = 0u
             |
             |    let mut i = 0u
             |    while (i <= $i) {
             |      c = a + b
             |      d = a - b
             |      e = c + d
             |      f = c * d
             |
             |      i = i + 1
             |    }
             |  }
             |}
             |""".stripMargin
        val script = Compiler.compileAssetScript(raw).rightValue
        val lockup = LockupScript.p2sh(script)
        val unlock = UnlockScript.p2sh(script, AVector(Val.U256(60), Val.U256(50)))

        transferFromP2sh(lockup, unlock)

        val estimator = assetScriptGasEstimator(lockup, unlock)
        GasEstimation.estimateInputGas(unlock, estimator).rightValue
      }

      p2shNoSignature(4) is GasBox.unsafe(2815)
      p2shNoSignature(59) is GasBox.unsafe(7491)
      p2shNoSignature(108) is GasBox.unsafe(11657)
    }

    info("P2SH, signatures required")

    {
      val (pubKey1, _) = keypairGen(groupIndex).sample.value

      val raw =
        s"""
           |// comment
           |AssetScript P2sh {
           |  pub fn main(pubKey1: ByteVec) -> () {
           |    verifyAbsoluteLocktime!(1630879601000)
           |    verifyTxSignature!(pubKey1)
           |  }
           |}
           |""".stripMargin

      val script = Compiler.compileAssetScript(raw).rightValue
      val lockup = LockupScript.p2sh(script)
      val unlock = UnlockScript.p2sh(script, AVector(Val.ByteVec(pubKey1.bytes)))

      transferFromP2sh(lockup, unlock)

      val estimator = assetScriptGasEstimator(lockup, unlock)
      GasEstimation
        .estimateInputGas(unlock, estimator)
        .leftValue is "Please use binary search to set the gas manually as signature is required in P2SH script"
    }

    info("P2SH, other execution error, e.g. ArithmeticError")

    {
      val raw =
        s"""
           |AssetScript Foo {
           |  pub fn bar(a: U256, b: U256) -> () {
           |    let mut c = 0u
           |    c = a - b
           |  }
           |}
           |""".stripMargin

      val script = Compiler.compileAssetScript(raw).rightValue
      val lockup = LockupScript.p2sh(script)
      val unlock = UnlockScript.p2sh(script, AVector(Val.U256(50), Val.U256(60)))

      transferFromP2sh(lockup, unlock)

      val estimator = assetScriptGasEstimator(lockup, unlock)
      GasEstimation
        .estimateInputGas(unlock, estimator)
        .leftValue
        .startsWith("Execution error when estimating gas for P2SH script: ArithmeticError") is true
    }
  }

  "GasEstimation.estimate" should "estimate the gas for TxScript correctly" in {

    info("Simple script, no signature required")

    {
      def simpleScript(i: Int): String = {
        s"""
           |@using(preapprovedAssets = false)
           |TxScript Main {
           |  let mut c = 0u
           |  let mut d = 0u
           |  let mut e = 0u
           |  let mut f = 0u
           |
           |  let mut i = 0u
           |  while (i <= $i) {
           |    c = 50 + 60
           |    d = 60 - 50
           |    e = c + d
           |    f = c * d
           |
           |    i = i + 1
           |  }
           |}
           |""".stripMargin
      }

      estimateTxScript(simpleScript(1)).rightValue is GasBox.unsafe(468)
      estimateTxScript(simpleScript(10)).rightValue is GasBox.unsafe(1198)
      estimateTxScript(simpleScript(100)).rightValue is GasBox.unsafe(8489)
    }

    info("Signature required")

    {
      val (pubKey, _) = keypairGen.sample.value
      estimateTxScript(
        s"""
           |TxScript Main {
           |  verifyTxSignature!(#${pubKey.toHexString})
           |}
           |""".stripMargin
      ).leftValue is "Please use binary search to set the gas manually as signature is required in tx script or contract"
    }

    info("Other execution error, e.g. AssertionFailed")

    {
      // scalastyle:off no.equal
      estimateTxScript(
        s"""
           |TxScript Main {
           |  assert!(1 == 2)
           |}
           |""".stripMargin
      ).leftValue is "Execution error when estimating gas for tx script or contract: AssertionFailed"
      // scalastyle:on no.equal
    }
  }

  private def transferFromP2sh(
      lockup: LockupScript.P2SH,
      unlock: UnlockScript
  ): Either[String, UnsignedTransaction] = {
    val group                 = lockup.groupIndex
    val (genesisPriKey, _, _) = genesisKeys(group.value)
    val block                 = transfer(blockFlow, genesisPriKey, lockup, ALPH.alph(2))
    val output                = AVector(TxOutputInfo(lockup, ALPH.alph(1), AVector.empty, None))
    addAndCheck(blockFlow, block)

    blockFlow
      .transfer(
        lockup,
        unlock,
        output,
        None,
        defaultGasPrice,
        defaultUtxoLimit
      )
      .rightValue
  }

  private def estimateTxScript(raw: String): Either[String, GasBox] = {
    val script            = Compiler.compileTxScript(raw).rightValue
    val chainIndex        = ChainIndex.unsafe(0, 0)
    val (_, publicKey, _) = genesisKeys(chainIndex.from.value)
    val lockup            = LockupScript.p2pkh(publicKey)
    val unlock            = UnlockScript.p2pkh(publicKey)
    val utxos             = blockFlow.getUsableUtxos(lockup, 100).rightValue
    val inputs            = utxos.map(_.ref).map(TxInput(_, unlock))
    val estimator         = TxScriptGasEstimator.Default(inputs, blockFlow)

    GasEstimation.estimate(script, estimator)
  }

  private def assetScriptGasEstimator(
      lockup: LockupScript.Asset,
      unlock: UnlockScript
  ): AssetScriptGasEstimator = {
    val inputs = blockFlow
      .getUsableUtxos(lockup, defaultUtxoLimit)
      .rightValue
      .map(_.ref)
      .map(TxInput(_, unlock))
    AssetScriptGasEstimator.Default(blockFlow).setInputs(inputs)
  }
}
