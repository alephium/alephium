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

import akka.util.ByteString
import org.scalatest.Assertion

import org.alephium.flow.FlowFixture
import org.alephium.flow.gasestimation._
import org.alephium.flow.mempool.MemPool
import org.alephium.flow.validation.TxValidation
import org.alephium.protocol.{ALPH, Generators, Hash, PrivateKey}
import org.alephium.protocol.model._
import org.alephium.protocol.model.UnsignedTransaction.TxOutputInfo
import org.alephium.protocol.vm.{GasBox, LockupScript, UnlockScript}
import org.alephium.util.{AlephiumSpec, AVector, TimeStamp, U256}

// scalastyle:off file.size.limit
class TxUtilsSpec extends AlephiumSpec {
  it should "consider use minimal gas fee" in new FlowFixture {
    val chainIndex            = ChainIndex.unsafe(0, 0)
    val (genesisPriKey, _, _) = genesisKeys(0)
    val (toPriKey, _)         = chainIndex.from.generateKey
    val block0 = transfer(blockFlow, genesisPriKey, toPriKey.publicKey, amount = dustUtxoAmount * 2)
    addAndCheck(blockFlow, block0)
    val block1 = transfer(blockFlow, genesisPriKey, toPriKey.publicKey, amount = dustUtxoAmount)
    addAndCheck(blockFlow, block1)

    blockFlow
      .transfer(
        toPriKey.publicKey,
        getGenesisLockupScript(chainIndex),
        None,
        dustUtxoAmount,
        None,
        minimalGasPrice,
        defaultUtxoLimit
      )
      .rightValue
      .isRight is true
  }

  it should "use default gas price" in new FlowFixture {
    val chainIndex            = ChainIndex.unsafe(0, 1)
    val (genesisPriKey, _, _) = genesisKeys(0)
    val (toPriKey, _)         = chainIndex.from.generateKey
    val block = transfer(blockFlow, genesisPriKey, toPriKey.publicKey, amount = dustUtxoAmount)
    val tx    = block.nonCoinbase.head
    tx.gasFeeUnsafe is defaultGasFee
    defaultGasFee is ALPH.nanoAlph(20000 * 100)
  }

  trait UnsignedTxFixture extends FlowFixture {
    def testUnsignedTx(unsignedTx: UnsignedTransaction, genesisPriKey: PrivateKey) = {
      val tx         = TransactionTemplate.from(unsignedTx, genesisPriKey)
      val chainIndex = tx.chainIndex
      TxValidation.build.validateGrandPoolTxTemplate(tx, blockFlow) isE ()
      blockFlow
        .getMemPool(chainIndex)
        .addNewTx(chainIndex, tx, TimeStamp.now()) is MemPool.AddedToSharedPool
      TxValidation.build.validateMempoolTxTemplate(tx, blockFlow) isE ()
    }
  }

  it should "consider outputs for inter-group blocks" in new UnsignedTxFixture {
    val chainIndex            = ChainIndex.unsafe(0, 1)
    val (genesisPriKey, _, _) = genesisKeys(0)
    val (_, toPubKey)         = chainIndex.to.generateKey
    val block                 = transfer(blockFlow, genesisPriKey, toPubKey, ALPH.alph(1))
    addAndCheck(blockFlow, block)

    val unsignedTx = blockFlow
      .transfer(
        genesisPriKey.publicKey,
        LockupScript.p2pkh(toPubKey),
        None,
        ALPH.cent(50),
        None,
        defaultGasPrice,
        defaultUtxoLimit
      )
      .rightValue
      .rightValue
    testUnsignedTx(unsignedTx, genesisPriKey)
  }

  trait PredefinedTxFixture extends UnsignedTxFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    def chainIndex: ChainIndex

    lazy val (_, toPubKey) = chainIndex.to.generateKey
    lazy val toLockup      = LockupScript.p2pkh(toPubKey)
    lazy val output0       = TxOutputInfo(toLockup, ALPH.alph(1), AVector.empty, None)

    lazy val (genesisPriKey, genesisPubKey, _) = genesisKeys(chainIndex.from.value)
    lazy val genesisLockup                     = LockupScript.p2pkh(genesisPubKey)
    lazy val genesisChange                     = genesisBalance - ALPH.alph(1) - defaultGasFee
    lazy val output1 = TxOutputInfo(genesisLockup, genesisChange, AVector.empty, None)
    lazy val unsignedTx = blockFlow
      .transfer(
        genesisPriKey.publicKey,
        AVector(output0, output1),
        Some(defaultGas),
        defaultGasPrice,
        defaultUtxoLimit
      )
      .rightValue
      .rightValue

    def test() = {
      unsignedTx.fixedOutputs.length is 2
      blockFlow
        .getBalance(genesisLockup, defaultUtxoLimit)
        .rightValue
        ._1 is genesisBalance
      testUnsignedTx(unsignedTx, genesisPriKey)
    }
  }

  it should "transfer ALPH with predefined value for intra-group txs" in new PredefinedTxFixture {
    override def chainIndex: ChainIndex =
      Generators.chainIndexGen.retryUntil(_.isIntraGroup).sample.get
    chainIndex.isIntraGroup is true
    test()
  }

  it should "transfer ALPH with predefined value for inter-group txs" in new PredefinedTxFixture {
    override def chainIndex: ChainIndex =
      Generators.chainIndexGen.retryUntil(!_.isIntraGroup).sample.get
    chainIndex.isIntraGroup is false
    test()
  }

  it should "calculate preOutputs for txs in new blocks" in new FlowFixture with Generators {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    forAll(groupIndexGen, groupIndexGen) { (fromGroup, toGroup) =>
      val chainIndex = ChainIndex(fromGroup, toGroup)

      val block = transfer(blockFlow, chainIndex)
      addAndCheck(blockFlow, block)

      val tx        = block.nonCoinbase.head
      val groupView = blockFlow.getMutableGroupView(chainIndex.from).rightValue
      groupView.getPreOutput(tx.unsigned.inputs.head.outputRef) isE None
      tx.assetOutputRefs.foreachWithIndex { case (outputRef, index) =>
        val output = tx.unsigned.fixedOutputs(index)
        if (output.toGroup equals chainIndex.from) {
          if (chainIndex.isIntraGroup) {
            // the block is persisted and the lockTime of each output is updated as block timestamp
            groupView.getPreOutput(outputRef) isE Some(output.copy(lockTime = block.timestamp))
          } else {
            // the block is not persisted yet, so the lockTime of each output is still zero
            groupView.getPreOutput(outputRef) isE Some(output)
          }
        } else {
          groupView.getPreOutput(outputRef) isE None
        }
      }
    }
  }

  it should "calculate preOutputs for txs in shared pool" in new FlowFixture with Generators {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    forAll(groupIndexGen, groupIndexGen) { (fromGroup, toGroup) =>
      val chainIndex = ChainIndex(fromGroup, toGroup)

      val block = transfer(blockFlow, chainIndex)
      val tx    = block.nonCoinbase.head
      blockFlow.getMemPool(chainIndex).addNewTx(chainIndex, tx.toTemplate, TimeStamp.now())

      {
        val groupView = blockFlow.getMutableGroupView(fromGroup).rightValue
        tx.assetOutputRefs.foreach { outputRef =>
          groupView.getPreOutput(outputRef) isE None
        }
      }

      {
        val groupView = blockFlow.getMutableGroupViewIncludePool(fromGroup).rightValue
        groupView.getPreOutput(tx.unsigned.inputs.head.outputRef) isE None
        tx.assetOutputRefs.foreachWithIndex { case (outputRef, index) =>
          val output = tx.unsigned.fixedOutputs(index)
          if (output.toGroup equals chainIndex.from) {
            groupView.getPreOutput(outputRef) isE Some(output)
          } else {
            groupView.getPreOutput(outputRef) isE None
          }
        }
      }
    }
  }

  trait UnsignedTransactionFixture extends FlowFixture {
    val chainIndex      = ChainIndex.unsafe(0, 0)
    val (_, fromPubKey) = chainIndex.to.generateKey
    val (_, toPubKey)   = chainIndex.to.generateKey

    val fromLockupScript = LockupScript.p2pkh(fromPubKey)
    val fromUnlockScript = UnlockScript.p2pkh(fromPubKey)
  }

  "UnsignedTransaction.build" should "build transaction successfully" in new UnsignedTransactionFixture {
    val inputs = {
      val input1 = input("input1", ALPH.oneAlph, fromLockupScript)
      val input2 = input("input2", ALPH.cent(50), fromLockupScript)

      AVector(input1, input2)
    }

    val outputs = {
      val output1 = output(LockupScript.p2pkh(toPubKey), ALPH.oneAlph)
      AVector(output1)
    }

    noException should be thrownBy {
      UnsignedTransaction
        .build(
          fromLockupScript,
          fromUnlockScript,
          inputs,
          outputs,
          minimalGas,
          defaultGasPrice
        )
        .rightValue
    }
  }

  it should "fail without enough ALPH" in new UnsignedTransactionFixture {
    val inputs = {
      val input1 = input("input1", ALPH.oneAlph, fromLockupScript)
      val input2 = input("input2", ALPH.cent(50), fromLockupScript)

      AVector(input1, input2)
    }

    val outputs = {
      val output1 = output(LockupScript.p2pkh(toPubKey), ALPH.alph(2))
      AVector(output1)
    }

    UnsignedTransaction
      .build(
        fromLockupScript,
        fromUnlockScript,
        inputs,
        outputs,
        minimalGas,
        defaultGasPrice
      )
      .leftValue is "Not enough balance"
  }

  it should "fail without enough Gas" in new UnsignedTransactionFixture {
    val inputs = {
      val input1 = input("input1", ALPH.oneAlph, fromLockupScript)
      AVector(input1)
    }

    val outputs = {
      val output1 = output(LockupScript.p2pkh(toPubKey), ALPH.oneAlph)
      AVector(output1)
    }

    UnsignedTransaction
      .build(
        fromLockupScript,
        fromUnlockScript,
        inputs,
        outputs,
        minimalGas,
        defaultGasPrice
      )
      .leftValue is "Not enough balance for gas fee"
  }

  it should "build transaction successfully with tokens" in new UnsignedTransactionFixture {
    val tokenId1 = Hash.hash("tokenId1")
    val tokenId2 = Hash.hash("tokenId2")

    val inputs = {
      val input1 = input("input1", ALPH.oneAlph, fromLockupScript, (tokenId2, U256.unsafe(10)))
      val input2 = input("input2", ALPH.alph(3), fromLockupScript, (tokenId1, U256.unsafe(50)))
      AVector(input1, input2)
    }

    val outputs = {
      val output1 = output(LockupScript.p2pkh(toPubKey), ALPH.oneAlph, (tokenId1, U256.unsafe(10)))
      val output2 = output(
        LockupScript.p2pkh(toPubKey),
        ALPH.alph(2),
        (tokenId2, U256.unsafe(9)),
        (tokenId1, U256.unsafe(39))
      )
      AVector(output1, output2)
    }

    val unsignedTx = UnsignedTransaction
      .build(
        fromLockupScript,
        fromUnlockScript,
        inputs,
        outputs,
        minimalGas,
        defaultGasPrice
      )
      .rightValue

    unsignedTx.fixedOutputs.length is 3

    info("verify change output")
    unsignedTx.fixedOutputs(2).amount is ALPH.oneAlph.subUnsafe(defaultGasFee)
    unsignedTx.fixedOutputs(2).tokens.length is 2
    unsignedTx.fixedOutputs(2).tokens.foreach { case (_, amount) =>
      amount is U256.unsafe(1)
    }
  }

  it should "fail when output has token that doesn't exist in input" in new UnsignedTransactionFixture {
    val tokenId1 = Hash.hash("tokenId1")
    val tokenId2 = Hash.hash("tokenId2")

    val inputs = {
      val input1 = input("input1", ALPH.oneAlph, fromLockupScript, (tokenId2, U256.unsafe(10)))
      val input2 = input("input2", ALPH.cent(50), fromLockupScript)
      AVector(input1, input2)
    }

    val outputs = {
      val output1 = output(LockupScript.p2pkh(toPubKey), ALPH.oneAlph, (tokenId1, U256.unsafe(10)))
      AVector(output1)
    }

    UnsignedTransaction
      .build(
        fromLockupScript,
        fromUnlockScript,
        inputs,
        outputs,
        minimalGas,
        defaultGasPrice
      )
      .leftValue is s"New tokens found in outputs: ${Set(tokenId1)}"
  }

  it should "fail without enough tokens" in new UnsignedTransactionFixture {
    val tokenId1 = Hash.hash("tokenId1")
    val tokenId2 = Hash.hash("tokenId2")

    val inputs = {
      val input1 = input("input1", ALPH.oneAlph, fromLockupScript, (tokenId2, U256.unsafe(10)))
      val input2 = input("input2", ALPH.alph(3), fromLockupScript, (tokenId1, U256.unsafe(50)))
      AVector(input1, input2)
    }

    val outputs = {
      val output1 = output(LockupScript.p2pkh(toPubKey), ALPH.oneAlph, (tokenId2, U256.unsafe(11)))
      AVector(output1)
    }

    UnsignedTransaction
      .build(
        fromLockupScript,
        fromUnlockScript,
        inputs,
        outputs,
        minimalGas,
        defaultGasPrice
      )
      .leftValue is s"Not enough balance for token $tokenId2"
  }

  it should "fail when outputs doesn't have minimal amount of Alph" in new UnsignedTransactionFixture {
    {
      info("with tokens")
      val tokenId1 = Hash.hash("tokenId1")
      val tokenId2 = Hash.hash("tokenId2")

      val inputs = {
        val input1 = input("input1", ALPH.oneAlph, fromLockupScript, (tokenId2, U256.unsafe(10)))
        val input2 = input("input2", ALPH.alph(3), fromLockupScript, (tokenId1, U256.unsafe(50)))
        AVector(input1, input2)
      }

      val outputs = {
        info(s"minimalAttoAlphAmountPerTxOutput is ${minimalAttoAlphAmountPerTxOutput(1)}")
        val output1 =
          output(LockupScript.p2pkh(toPubKey), ALPH.nanoAlph(900), (tokenId2, U256.unsafe(11)))
        AVector(output1)
      }

      UnsignedTransaction
        .build(
          fromLockupScript,
          fromUnlockScript,
          inputs,
          outputs,
          minimalGas,
          defaultGasPrice
        )
        .leftValue is "Not enough ALPH for transaction output"
    }

    {
      info("without tokens")
      val inputs = {
        val input1 = input("input1", ALPH.oneAlph, fromLockupScript)
        val input2 = input("input2", ALPH.alph(3), fromLockupScript)
        AVector(input1, input2)
      }

      val outputs = {
        info(s"minimalAttoAlphAmountPerTxOutput is ${minimalAttoAlphAmountPerTxOutput(0)}")
        val output1 = output(LockupScript.p2pkh(toPubKey), ALPH.nanoAlph(900))
        AVector(output1)
      }

      UnsignedTransaction
        .build(
          fromLockupScript,
          fromUnlockScript,
          inputs,
          outputs,
          minimalGas,
          defaultGasPrice
        )
        .leftValue is "Not enough ALPH for transaction output"
    }
  }

  it should "fail when change output doesn't have minimal amount of Alph" in new UnsignedTransactionFixture {
    {
      info("with tokens")
      val tokenId1 = Hash.hash("tokenId1")
      val tokenId2 = Hash.hash("tokenId2")

      val inputs = {
        val input1Amount = defaultGasFee.addUnsafe(minimalAttoAlphAmountPerTxOutput(1)).subUnsafe(1)
        val input1 = input("input1", input1Amount, fromLockupScript, (tokenId2, U256.unsafe(10)))
        val input2 = input("input2", ALPH.alph(3), fromLockupScript, (tokenId1, U256.unsafe(50)))
        AVector(input1, input2)
      }

      val outputs = {
        val output1 =
          output(LockupScript.p2pkh(toPubKey), ALPH.oneAlph, (tokenId1, U256.unsafe(10)))
        val output2 = output(
          LockupScript.p2pkh(toPubKey),
          ALPH.alph(2),
          (tokenId2, U256.unsafe(9)),
          (tokenId1, U256.unsafe(39))
        )
        AVector(output1, output2)
      }

      UnsignedTransaction
        .build(
          fromLockupScript,
          fromUnlockScript,
          inputs,
          outputs,
          minimalGas,
          defaultGasPrice
        )
        .leftValue is "Not enough ALPH for change output"
    }

    {
      info("without tokens")
      val inputs = {
        val input1Amount = defaultGasFee.addUnsafe(minimalAttoAlphAmountPerTxOutput(0)).subUnsafe(1)
        val input1       = input("input1", input1Amount, fromLockupScript)
        val input2       = input("input2", ALPH.alph(3), fromLockupScript)
        AVector(input1, input2)
      }

      val outputs = {
        val output1 = output(LockupScript.p2pkh(toPubKey), ALPH.oneAlph)
        val output2 = output(LockupScript.p2pkh(toPubKey), ALPH.alph(2))
        AVector(output1, output2)
      }

      UnsignedTransaction
        .build(
          fromLockupScript,
          fromUnlockScript,
          inputs,
          outputs,
          minimalGas,
          defaultGasPrice
        )
        .leftValue is "Not enough ALPH for change output"
    }
  }

  it should "fail when inputs are not unique" in new UnsignedTransactionFixture {
    val inputs = {
      val input1 = input("input1", ALPH.alph(4), fromLockupScript)
      val input2 = input("input1", ALPH.alph(3), fromLockupScript)
      AVector(input1, input2)
    }

    val outputs = AVector(output(LockupScript.p2pkh(toPubKey), ALPH.oneAlph))

    UnsignedTransaction
      .build(
        fromLockupScript,
        fromUnlockScript,
        inputs,
        outputs,
        minimalGas,
        defaultGasPrice
      )
      .leftValue is "Inputs not unique"
  }

  it should "fail when there are too many tokens in the transaction output" in new UnsignedTransactionFixture {
    val inputs = AVector(input("input", ALPH.alph(3), fromLockupScript))
    val outputs = {
      val tokens = AVector.tabulate(maxTokenPerUtxo + 1) { i =>
        val tokenId = Hash.hash(s"tokenId$i")
        (tokenId, U256.unsafe(1))
      }

      val output1 = output(LockupScript.p2pkh(toPubKey), ALPH.oneAlph, tokens.toSeq: _*)
      AVector(output1)
    }

    UnsignedTransaction
      .build(
        fromLockupScript,
        fromUnlockScript,
        inputs,
        outputs,
        minimalGas,
        defaultGasPrice
      )
      .leftValue is "Too many tokens in the transaction output, maximal number 4"
  }

  it should "fail when there are tokens with zero value in the transaction output" in new UnsignedTransactionFixture {
    val tokenId1 = Hash.hash("tokenId1")
    val tokenId2 = Hash.hash("tokenId2")
    val inputs = AVector(
      input("input", ALPH.alph(3), fromLockupScript, (tokenId1, U256.Zero), (tokenId2, U256.Two))
    )
    val outputs = {
      val output1 = output(
        LockupScript.p2pkh(toPubKey),
        ALPH.alph(1),
        (tokenId1, U256.Zero),
        (tokenId2, U256.Two)
      )
      val output2 = output(LockupScript.p2pkh(toPubKey), ALPH.alph(2), (tokenId1, U256.One))
      AVector(output1, output2)
    }

    UnsignedTransaction
      .build(
        fromLockupScript,
        fromUnlockScript,
        inputs,
        outputs,
        minimalGas,
        defaultGasPrice
      )
      .leftValue is "Value is Zero for one or many tokens in the transaction output"
  }

  it should "estimate gas for sweep all tx" in new FlowFixture {
    val txValidation = TxValidation.build
    def test(inputNum: Int) = {
      val blockflow = isolatedBlockFlow()
      val block     = transfer(blockflow, ChainIndex.unsafe(0, 0))
      val tx        = block.nonCoinbase.head
      val output    = tx.unsigned.fixedOutputs.head
      val outputs   = AVector.fill(inputNum)(output.copy(amount = ALPH.oneAlph))
      val newTx     = Transaction.from(tx.unsigned.inputs, outputs, tx.inputSignatures)
      val newBlock  = block.copy(transactions = AVector(newTx))
      addAndUpdateView(blockflow, newBlock)

      val unsignedTxs = blockflow
        .sweepAddress(
          keyManager(output.lockupScript).publicKey,
          output.lockupScript,
          None,
          None,
          defaultGasPrice,
          defaultUtxoLimit
        )
        .rightValue
        .rightValue
      unsignedTxs.length is 1
      val unsignedTx = unsignedTxs.head
      unsignedTx.fixedOutputs.length is 1
      unsignedTx.gasAmount is GasEstimation.sweepAddress(inputNum, 1)
      val sweepTx = Transaction.from(unsignedTx, keyManager(output.lockupScript))
      txValidation.validateTxOnlyForTest(sweepTx, blockflow) isE ()
    }

    (1 to 10).foreach(test)
  }

  it should "create multiple outputs for sweep all tx if too many tokens" in new FlowFixture
    with LockupScriptGenerators {
    case class Test(tokens: AVector[(TokenId, U256)], attoAlphAmount: U256 = ALPH.alph(3)) {
      val groupIndex       = groupIndexGen.sample.value
      val toLockupScript   = p2pkhLockupGen(groupIndex).sample.value
      val fromLockupScript = p2pkhLockupGen(groupIndex).sample.value
      val output = AssetOutput(
        attoAlphAmount,
        fromLockupScript,
        TimeStamp.unsafe(0),
        tokens,
        additionalData = ByteString(0)
      )

      val result = TxUtils
        .buildSweepAddressTxOutputsWithGas(
          toLockupScript,
          lockTimeOpt = None,
          AVector(output),
          gasOpt = None,
          defaultGasPrice
        )

      def success(verify: ((AVector[TxOutputInfo], GasBox)) => Assertion) = {
        verify(result.rightValue)
      }

      def failed(verify: (String) => Assertion) = {
        verify(result.leftValue)
      }
    }

    def verifyExtraOutput(output: TxOutputInfo) = {
      output.attoAlphAmount is minimalAttoAlphAmountPerTxOutput(maxTokenPerUtxo)
      output.tokens.length is maxTokenPerUtxo
    }

    {
      info("no tokens")
      Test(AVector.empty).success { case (outputs, gas) =>
        outputs.length is 1
        gas is GasEstimation.sweepAddress(1, 1)
      }
    }

    {
      info("token amount not more than `maxTokenPerUtxo`")
      val tokens = AVector.tabulate(maxTokenPerUtxo) { i =>
        val tokenId = Hash.hash(s"tokenId$i")
        (tokenId, U256.unsafe(1))
      }

      Test(tokens).success { case (outputs, gas) =>
        outputs.length is 1
        gas is GasEstimation.sweepAddress(1, 1)
      }
    }

    {
      info("token amount more than `maxTokenPerUtxo`")
      val tokens = AVector.tabulate(maxTokenPerUtxo + 1) { i =>
        val tokenId = Hash.hash(s"tokenId$i")
        (tokenId, U256.unsafe(1))
      }

      Test(tokens).success { case (outputs, gas) =>
        outputs.length is 2

        outputs(0).attoAlphAmount is ALPH
          .alph(3)
          .subUnsafe(minimalAttoAlphAmountPerTxOutput(maxTokenPerUtxo))
          .subUnsafe(defaultGasPrice * gas)
        outputs(0).tokens.length is 1

        verifyExtraOutput(outputs(1))

        gas is GasEstimation.sweepAddress(1, 2)
      }
    }

    {
      info("token amount a bit more than two times of `maxTokenPerUtxo`")
      val tokens = AVector.tabulate(2 * maxTokenPerUtxo + 1) { i =>
        val tokenId = Hash.hash(s"tokenId$i")
        (tokenId, U256.unsafe(1))
      }

      Test(tokens).success { case (outputs, gas) =>
        outputs.length is 3

        outputs(0).attoAlphAmount is ALPH
          .alph(3)
          .subUnsafe(minimalAttoAlphAmountPerTxOutput(maxTokenPerUtxo).mulUnsafe(2))
          .subUnsafe(defaultGasPrice * gas)
        outputs(0).tokens.length is 1

        verifyExtraOutput(outputs(1))
        verifyExtraOutput(outputs(2))

        gas is GasEstimation.sweepAddress(1, 3)
      }
    }

    {
      info("token amount three times of `maxTokenPerUtxo`")
      val tokens = AVector.tabulate(3 * maxTokenPerUtxo) { i =>
        val tokenId = Hash.hash(s"tokenId$i")
        (tokenId, U256.unsafe(1))
      }

      Test(tokens).success { case (outputs, gas) =>
        outputs.length is 3

        outputs(0).attoAlphAmount is ALPH
          .alph(3)
          .subUnsafe(minimalAttoAlphAmountPerTxOutput(maxTokenPerUtxo).mulUnsafe(2))
          .subUnsafe(defaultGasPrice * gas)
        outputs(0).tokens.length is maxTokenPerUtxo

        verifyExtraOutput(outputs(1))
        verifyExtraOutput(outputs(2))

        gas is GasEstimation.sweepAddress(1, 3)
      }
    }

    {
      info("The amount in the first output is below minimalAttoAlphAmountPerTxOutput(tokens)")
      val attoAlphAmount = minimalAttoAlphAmountPerTxOutput(maxTokenPerUtxo - 1)
        .addUnsafe(defaultGasPrice * GasEstimation.sweepAddress(1, 3))
        .addUnsafe(minimalAttoAlphAmountPerTxOutput(maxTokenPerUtxo).mulUnsafe(2))

      val tokens = AVector.tabulate(3 * maxTokenPerUtxo - 1) { i =>
        val tokenId = Hash.hash(s"tokenId$i")
        (tokenId, U256.unsafe(1))
      }

      Test(tokens, attoAlphAmount).success { case (outputs, gas) =>
        outputs.length is 3

        outputs(0).attoAlphAmount is minimalAttoAlphAmountPerTxOutput(maxTokenPerUtxo - 1)
        outputs(0).tokens.length is maxTokenPerUtxo - 1

        verifyExtraOutput(outputs(1))
        verifyExtraOutput(outputs(2))

        gas is GasEstimation.sweepAddress(1, 3)
      }

      Test(tokens, attoAlphAmount.subUnsafe(1))
        .failed(_ is "Not enough ALPH balance for transaction outputs")
    }
  }

  "TxUtils.getFirstOutputTokensNum" should "return the number of tokens for the first output of the sweepAddress transaction" in {
    TxUtils.getFirstOutputTokensNum(0) is 0
    TxUtils.getFirstOutputTokensNum(maxTokenPerUtxo) is maxTokenPerUtxo
    TxUtils.getFirstOutputTokensNum(maxTokenPerUtxo + 1) is 1
    TxUtils.getFirstOutputTokensNum(maxTokenPerUtxo + 10) is 2
    TxUtils.getFirstOutputTokensNum(maxTokenPerUtxo + maxTokenPerUtxo - 1) is maxTokenPerUtxo - 1
    TxUtils.getFirstOutputTokensNum(maxTokenPerUtxo * 10) is maxTokenPerUtxo
  }

  trait LargeUtxos extends FlowFixture {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val block      = transfer(blockFlow, chainIndex)
    val tx         = block.nonCoinbase.head
    val output     = tx.unsigned.fixedOutputs.head

    val n = 3 * ALPH.MaxTxInputNum

    val outputs  = AVector.fill(n)(output.copy(amount = ALPH.oneAlph))
    val newTx    = Transaction.from(tx.unsigned.inputs, outputs, tx.inputSignatures)
    val newBlock = block.copy(transactions = AVector(newTx))
    addAndUpdateView(blockFlow, newBlock)

    val (balance, lockedBalance, numOfUtxos) =
      blockFlow.getBalance(output.lockupScript, Int.MaxValue).rightValue
    balance is U256.unsafe(outputs.sumBy(_.amount.toBigInt))
    lockedBalance is 0
    numOfUtxos is n
  }

  it should "get all available utxos" in new LargeUtxos {
    val fetchedUtxos = blockFlow.getUsableUtxos(output.lockupScript, n).rightValue
    fetchedUtxos.length is n
  }

  it should "transfer with large amount of UTXOs with provided gas" in new LargeUtxos {
    val txValidation = TxValidation.build
    val unsignedTx0 = blockFlow
      .transfer(
        keyManager(output.lockupScript).publicKey,
        output.lockupScript,
        None,
        ALPH.alph((ALPH.MaxTxInputNum - 1).toLong),
        Some(GasBox.unsafe(600000)),
        defaultGasPrice,
        defaultUtxoLimit
      )
      .rightValue
      .rightValue
    val tx0 = Transaction.from(unsignedTx0, keyManager(output.lockupScript))
    tx0.unsigned.inputs.length is ALPH.MaxTxInputNum
    tx0.inputSignatures.length is 1
    txValidation.validateTxOnlyForTest(tx0, blockFlow) isE ()

    blockFlow
      .transfer(
        keyManager(output.lockupScript).publicKey,
        output.lockupScript,
        None,
        ALPH.alph(ALPH.MaxTxInputNum.toLong),
        Some(GasBox.unsafe(600000)),
        defaultGasPrice,
        defaultUtxoLimit
      )
      .rightValue
      .leftValue is "Too many inputs for the transfer, consider to reduce the amount to send, or use the `sweep-address` endpoint to consolidate the inputs first"
  }

  it should "transfer with large amount of UTXOs with estimated gas" in new LargeUtxos {
    val maxP2PKHInputsAllowedByGas = 151

    info("With provided Utxos")

    val availableUtxos = blockFlow.getUTXOsIncludePool(output.lockupScript, Int.MaxValue).rightValue
    val availableInputs = availableUtxos.map(_.ref)
    val outputInfo = AVector(
      TxOutputInfo(
        output.lockupScript,
        ALPH.alph(1),
        AVector.empty,
        None
      )
    )

    blockFlow
      .transfer(
        keyManager(output.lockupScript).publicKey,
        availableInputs.take(maxP2PKHInputsAllowedByGas),
        outputInfo,
        None,
        defaultGasPrice
      )
      .rightValue
      .rightValue
      .inputs
      .length is maxP2PKHInputsAllowedByGas

    blockFlow
      .transfer(
        keyManager(output.lockupScript).publicKey,
        availableInputs.take(maxP2PKHInputsAllowedByGas + 1),
        outputInfo,
        None,
        defaultGasPrice
      )
      .rightValue
      .leftValue is "Estimated gas GasBox(627120) too large, maximal GasBox(625000). Consider consolidating UTXOs using the sweep endpoints"

    info("Without provided Utxos")

    blockFlow
      .transfer(
        keyManager(output.lockupScript).publicKey,
        output.lockupScript,
        None,
        ALPH.alph(maxP2PKHInputsAllowedByGas.toLong - 1),
        None,
        defaultGasPrice,
        defaultUtxoLimit
      )
      .rightValue
      .rightValue
      .inputs
      .length is maxP2PKHInputsAllowedByGas

    blockFlow
      .transfer(
        keyManager(output.lockupScript).publicKey,
        output.lockupScript,
        None,
        ALPH.alph(maxP2PKHInputsAllowedByGas.toLong),
        None,
        defaultGasPrice,
        defaultUtxoLimit
      )
      .rightValue
      .leftValue is "Estimated gas GasBox(627120) too large, maximal GasBox(625000). Consider consolidating UTXOs using the sweep endpoints"
  }

  it should "sweep as much as we can" in new LargeUtxos {
    val txValidation = TxValidation.build

    val unsignedTxs = blockFlow
      .sweepAddress(
        keyManager(output.lockupScript).publicKey,
        output.lockupScript,
        None,
        None,
        defaultGasPrice,
        Int.MaxValue
      )
      .rightValue
      .rightValue

    unsignedTxs.length is 6

    unsignedTxs.foreach { unsignedTx =>
      val sweepTx = Transaction.from(unsignedTx, keyManager(output.lockupScript))
      txValidation.validateTxOnlyForTest(sweepTx, blockFlow) isE ()
    }
  }

  private def input(
      name: String,
      amount: U256,
      lockupScript: LockupScript.Asset,
      tokens: (TokenId, U256)*
  ): (AssetOutputRef, AssetOutput) = {
    val ref = AssetOutputRef.unsafeWithScriptHint(new ScriptHint(0), Hash.hash(name))
    val output = AssetOutput(
      amount,
      lockupScript,
      lockTime = TimeStamp.zero,
      tokens = AVector.from(tokens),
      additionalData = ByteString.empty
    )

    (ref, output)
  }

  private def output(
      lockupScript: LockupScript.Asset,
      attoAlphAmount: U256,
      tokens: (TokenId, U256)*
  ): UnsignedTransaction.TxOutputInfo = {
    UnsignedTransaction.TxOutputInfo(
      lockupScript,
      attoAlphAmount,
      AVector.from(tokens),
      lockTime = None
    )
  }
}
