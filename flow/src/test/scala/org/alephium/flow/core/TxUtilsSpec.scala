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

import scala.util.Random

import akka.util.ByteString
import org.scalacheck.Gen
import org.scalatest.{Assertion, Succeeded}

import org.alephium.crypto.BIP340Schnorr
import org.alephium.flow.{AlephiumFlowBasicSpec, FlowFixture}
import org.alephium.flow.core.ExtraUtxosInfo
import org.alephium.flow.core.FlowUtils.{
  AssetOutputInfo,
  MemPoolOutput,
  OutputType,
  PersistedOutput,
  UnpersistedBlockOutput
}
import org.alephium.flow.gasestimation._
import org.alephium.flow.mempool.MemPool
import org.alephium.flow.setting.AlephiumConfigFixture
import org.alephium.flow.validation.TxValidation
import org.alephium.protocol._
import org.alephium.protocol.mining.Emission
import org.alephium.protocol.model._
import org.alephium.protocol.model.UnsignedTransaction.TxOutputInfo
import org.alephium.protocol.vm._
import org.alephium.ralph.Compiler
import org.alephium.util.{AVector, TimeStamp, U256}

// scalastyle:off file.size.limit
class TxUtilsSpec extends AlephiumFlowBasicSpec {
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
        coinbaseGasPrice,
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
    tx.gasFeeUnsafe is nonCoinbaseMinGasFee
    nonCoinbaseMinGasFee is ALPH.nanoAlph(20000 * 100)
  }

  trait UnsignedTxFixture extends FlowFixture {
    def testUnsignedTx(unsignedTx: UnsignedTransaction, genesisPriKey: PrivateKey) = {
      val tx         = TransactionTemplate.from(unsignedTx, genesisPriKey)
      val chainIndex = tx.chainIndex
      TxValidation.build.validateMempoolTxTemplate(tx, blockFlow) isE ()
      blockFlow
        .getGrandPool()
        .add(chainIndex, tx, TimeStamp.now()) is MemPool.AddedToMemPool
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
        nonCoinbaseMinGasPrice,
        defaultUtxoLimit
      )
      .rightValue
      .rightValue
    testUnsignedTx(unsignedTx, genesisPriKey)
  }

  trait PredefinedTxFixture extends UnsignedTxFixture {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))

    def chainIndex: ChainIndex

    lazy val (_, toPubKey) = chainIndex.to.generateKey
    lazy val toLockup      = LockupScript.p2pkh(toPubKey)
    lazy val output0       = TxOutputInfo(toLockup, ALPH.alph(1), AVector.empty, None)

    lazy val (genesisPriKey, genesisPubKey, _) = genesisKeys(chainIndex.from.value)
    lazy val genesisLockup                     = LockupScript.p2pkh(genesisPubKey)
    lazy val genesisChange = genesisBalance - ALPH.alph(1) - nonCoinbaseMinGasFee
    lazy val unsignedTx = blockFlow
      .transfer(
        genesisPriKey.publicKey,
        AVector(output0),
        Some(minimalGas),
        nonCoinbaseMinGasPrice,
        defaultUtxoLimit,
        ExtraUtxosInfo.empty
      )
      .rightValue
      .rightValue

    def test() = {
      unsignedTx.fixedOutputs.length is 2
      unsignedTx.fixedOutputs(0).amount is ALPH.oneAlph
      unsignedTx.fixedOutputs(1).amount is genesisChange
      blockFlow
        .getBalance(genesisLockup, defaultUtxoLimit, true)
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

  it should "getPreContractOutput for txs in new blocks" in new ContractFixture {
    blockFlow
      .getMutableGroupView(GroupIndex.Zero)
      .rightValue
      .getPreContractOutput(contractOutputRef)
      .rightValue
      .get
      .lockupScript is contractOutputScript
  }

  it should "calculate getPreAssetOutputInfo for txs in new blocks" in new FlowFixture
    with Generators {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))

    forAll(groupIndexGen, groupIndexGen) { (fromGroup, toGroup) =>
      val chainIndex = ChainIndex(fromGroup, toGroup)

      val block = transfer(blockFlow, chainIndex)
      addAndCheck(blockFlow, block)

      val tx        = block.nonCoinbase.head
      val groupView = blockFlow.getMutableGroupView(chainIndex.from).rightValue
      // return None when output is spent
      groupView.getPreAssetOutputInfo(tx.unsigned.inputs.head.outputRef) isE None
      tx.fixedOutputRefs.foreachWithIndex { case (outputRef, index) =>
        val output = tx.unsigned.fixedOutputs(index)
        if (output.toGroup equals chainIndex.from) {
          if (chainIndex.isIntraGroup) {
            // the block is persisted and the lockTime of each output is updated as block timestamp
            groupView.getPreAssetOutputInfo(outputRef) isE Some(
              AssetOutputInfo(
                outputRef,
                output.copy(lockTime = block.timestamp),
                PersistedOutput
              )
            )
          } else {
            // the block is not persisted yet, so the lockTime of each output is still zero
            groupView.getPreAssetOutputInfo(outputRef) isE Some(
              AssetOutputInfo(outputRef, output, UnpersistedBlockOutput)
            )
          }
        } else {
          // return None for transaction output to different group
          groupView.getPreAssetOutputInfo(outputRef) isE None
        }
      }
    }
  }

  it should "calculate getPreAssetOutputInfo for txs in mempool" in new FlowFixture
    with Generators {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))

    forAll(groupIndexGen, groupIndexGen) { (fromGroup, toGroup) =>
      val chainIndex = ChainIndex(fromGroup, toGroup)

      val block = transfer(blockFlow, chainIndex)
      val tx    = block.nonCoinbase.head
      blockFlow.getGrandPool().add(chainIndex, tx.toTemplate, TimeStamp.now())

      {
        val groupView = blockFlow.getMutableGroupView(fromGroup).rightValue
        tx.fixedOutputRefs.foreach { outputRef =>
          groupView.getPreAssetOutputInfo(outputRef) isE None
        }
      }

      {
        val groupView = blockFlow.getMutableGroupViewIncludePool(fromGroup).rightValue
        // return None when output is spent
        groupView.getPreAssetOutputInfo(tx.unsigned.inputs.head.outputRef) isE None
        tx.fixedOutputRefs.foreachWithIndex { case (outputRef, index) =>
          val output = tx.unsigned.fixedOutputs(index)
          if (output.toGroup equals chainIndex.from) {
            groupView.getPreAssetOutputInfo(outputRef) isE Some(
              AssetOutputInfo(outputRef, output, MemPoolOutput)
            )
          } else {
            // MemPool.isSpent throws when asking for output to different group
            assertThrows[AssertionError](groupView.getPreAssetOutputInfo(outputRef))
          }
        }
      }
    }
  }

  it should "calculate getPreContractOutput" in new FlowFixture {
    val fromGroup = GroupIndex.unsafe(0)
    val toGroup   = GroupIndex.unsafe(0)
    val contractCode =
      s"""
         |Contract Foo() {
         |  fn foo() -> () {
         |    return
         |  }
         |}
         |""".stripMargin

    val chainIndex    = ChainIndex(fromGroup, toGroup)
    val contract      = Compiler.compileContract(contractCode).rightValue
    val genesisLockup = getGenesisLockupScript(chainIndex)
    val txScript =
      contractCreation(
        contract,
        AVector.empty,
        AVector.empty,
        genesisLockup,
        minimalAlphInContract
      )
    val block = payableCall(blockFlow, chainIndex, txScript)
    val contractOutputRef =
      TxOutputRef.unsafe(block.transactions.head, 0).asInstanceOf[ContractOutputRef]
    val contractId           = ContractId.from(block.transactions.head.id, 0, chainIndex.from)
    val contractOutputScript = LockupScript.p2c(contractId)

    {
      // it should be absent in mempool
      val tx = block.nonCoinbase.head
      blockFlow.getGrandPool().add(chainIndex, tx.toTemplate, TimeStamp.now())
      blockFlow
        .getMutableGroupViewIncludePool(fromGroup)
        .rightValue
        .getPreContractOutput(contractOutputRef)
        .rightValue
        .isEmpty is true
    }

    {
      // it should be present in Persisted state
      addAndCheck(blockFlow, block)
      blockFlow.getMutableGroupView(chainIndex.from).rightValue
      blockFlow
        .getMutableGroupView(fromGroup)
        .rightValue
        .getPreContractOutput(contractOutputRef)
        .rightValue
        .get
        .lockupScript is contractOutputScript
    }
  }

  trait UnsignedTransactionFixture extends FlowFixture {
    val chainIndex      = ChainIndex.unsafe(0, 0)
    val (_, fromPubKey) = chainIndex.to.generateKey
    val (_, toPubKey)   = chainIndex.to.generateKey

    val fromLockupScript = LockupScript.p2pkh(fromPubKey)
    val fromUnlockScript = UnlockScript.p2pkh(fromPubKey)
  }

  trait MultiInputTransactionFixture extends UnsignedTransactionFixture {
    val (genesisPriKey, genesisPubKey, _) = genesisKeys(0)
    val (_, pub1)                         = chainIndex.from.generateKey
    val (_, pub2)                         = chainIndex.from.generateKey
    val (_, pub3)                         = chainIndex.from.generateKey
    val (_, pub4)                         = chainIndex.from.generateKey

    val amount = 10L

    val inputData = TxUtils.InputData(
      fromLockupScript,
      fromUnlockScript,
      ALPH.alph(1),
      None,
      None,
      None
    )

    def buildInputsWithGas(
        nb: Int,
        gas: GasBox,
        outputType: OutputType = MemPoolOutput
    ): TxUtils.AssetOutputInfoWithGas =
      TxUtils.AssetOutputInfoWithGas(
        AVector.fill(nb) {
          val (ref, output) = input("input1", ALPH.alph(amount), fromLockupScript)
          AssetOutputInfo(ref, output, outputType)
        },
        gas
      )

    def buildInputData(
        pubKey: PublicKey,
        alph: Long,
        gas: Option[GasBox] = None,
        utxos: Option[AVector[AssetOutputRef]] = None,
        tokens: Option[AVector[(TokenId, U256)]] = None
    ) =
      TxUtils.InputData(
        LockupScript.p2pkh(pubKey),
        UnlockScript.p2pkh(pubKey),
        ALPH.alph(alph),
        tokens,
        gas,
        utxos
      )

    def genKeys(nb: Int): (AVector[PublicKey], AVector[PrivateKey]) = {
      val keys        = AVector.fill(nb)(chainIndex.from.generateKey)
      val publicKeys  = keys.map(_._2)
      val privateKeys = keys.map(_._1)
      (publicKeys, privateKeys)
    }

    def computeAmountPerOutput(totalAmount: Long, nbOfOutputs: Int): (Long, Long) = {
      val amountPerOutput = totalAmount / nbOfOutputs
      val rest            = totalAmount % nbOfOutputs
      (amountPerOutput, rest)
    }

    def buildBlock(
        pubKey: PublicKey,
        transferAmount: Long,
        tokensOpt: Option[AVector[(TokenId, U256)]] = None
    ) = {
      tokensOpt match {
        case None =>
          transfer(blockFlow, genesisPriKey, pubKey, amount = ALPH.alph(transferAmount))
        case Some(tokens) =>
          val lockupScript = Address.p2pkh(pubKey).lockupScript
          transfer(
            blockFlow,
            genesisPriKey,
            lockupScript,
            tokens = tokens,
            amount = ALPH.alph(transferAmount)
          )
      }
    }

    def validateSubmit(utx: UnsignedTransaction, privateKeys: AVector[PrivateKey]) = {

      val signatures = privateKeys.map { privateKey =>
        SignatureSchema.sign(utx.id.bytes, privateKey)
      }

      val template = TransactionTemplate(
        utx,
        signatures,
        scriptSignatures = AVector.empty
      )

      val txValidation = TxValidation.build

      txValidation.validateMempoolTxTemplate(template, blockFlow) is Right(())
    }

    def buildOutputs(nbOfOutputs: Int, totalAmount: Long) = {
      val (amountPerOutput, rest) = computeAmountPerOutput(totalAmount, nbOfOutputs)
      AVector.fill(nbOfOutputs)(chainIndex.from.generateKey._2).mapWithIndex { (pubKey, i) =>
        val amount = if (i == 0) amountPerOutput + rest else amountPerOutput
        TxOutputInfo(LockupScript.p2pkh(pubKey), ALPH.alph(amount), AVector.empty, None)
      }
    }

    // scalastyle:off method.length
    def checkMultiInputTx(nbOfInputs: Int, nbOfOutputs: Int) = {

      val (publicKeys, privateKeys) = genKeys(nbOfInputs)

      publicKeys.foreach { pubKey =>
        val block = buildBlock(pubKey, 100)
        addAndCheck(blockFlow, block)
      }

      val inputs = publicKeys.map { pubKey =>
        buildInputData(pubKey, amount)
      }

      val totalAmount             = amount * nbOfInputs
      val (amountPerOutput, rest) = computeAmountPerOutput(totalAmount, nbOfOutputs)

      val outputs = buildOutputs(nbOfOutputs, totalAmount)

      val utx = blockFlow
        .transferMultiInputs(
          inputs,
          outputs,
          nonCoinbaseMinGasPrice,
          Int.MaxValue,
          None
        )
        .rightValue
        .rightValue

      utx.inputs.length is nbOfInputs
      utx.fixedOutputs.length is (nbOfInputs + nbOfOutputs)

      val fixedOutputs = utx.fixedOutputs.take(nbOfOutputs)
      fixedOutputs.head.amount is ALPH.alph(amountPerOutput + rest)
      fixedOutputs.tail.foreach(_.amount is ALPH.alph(amountPerOutput))

      val gasEstimation = estimateWithDifferentP2PKHInputs(nbOfInputs, nbOfOutputs + nbOfInputs)

      utx.gasAmount <= gasEstimation is true

      val changeOutputs = utx.fixedOutputs.drop(nbOfOutputs).map(_.amount)

      // As they all had 1 utxo, they all had to pay same gas, so same change output
      // Maybe first input will pay the rest of base fee, so it could be 2
      changeOutputs.toSeq.distinct.length <= 2 is true

      validateSubmit(utx, privateKeys)
    }

    def estimateWithDifferentP2PKHInputs(numInputs: Int, numOutputs: Int): GasBox = {
      val inputGas =
        GasSchedule.txInputBaseGas.addUnsafe(GasSchedule.p2pkUnlockGas).mulUnsafe(numInputs)
      GasEstimation.estimate(inputGas, numOutputs)
    }
  }

  "UnsignedTransaction.buildTransferTx" should "build transaction successfully" in new UnsignedTransactionFixture {
    val inputs = {
      val input1 = input("input1", ALPH.oneAlph, fromLockupScript)
      val input2 = input("input2", ALPH.cent(50), fromLockupScript)

      AVector(input1, input2)
    }

    val outputs = {
      val output1 = output(LockupScript.p2pkh(toPubKey), ALPH.oneAlph)
      AVector(output1)
    }

    val unsignedTx = UnsignedTransaction
      .buildTransferTx(
        fromLockupScript,
        fromUnlockScript,
        inputs,
        outputs,
        minimalGas,
        nonCoinbaseMinGasPrice
      )
      .rightValue
    unsignedTx.inputs.head.unlockScript is fromUnlockScript
    unsignedTx.inputs.tail.foreach(_.unlockScript is UnlockScript.SameAsPrevious)
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
      .buildTransferTx(
        fromLockupScript,
        fromUnlockScript,
        inputs,
        outputs,
        minimalGas,
        nonCoinbaseMinGasPrice
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
      .buildTransferTx(
        fromLockupScript,
        fromUnlockScript,
        inputs,
        outputs,
        minimalGas,
        nonCoinbaseMinGasPrice
      )
      .leftValue is "Not enough balance for gas fee"
  }

  it should "build transaction successfully with tokens" in new UnsignedTransactionFixture {
    val tokenId1 = TokenId.hash("tokenId1")
    val tokenId2 = TokenId.hash("tokenId2")

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
      .buildTransferTx(
        fromLockupScript,
        fromUnlockScript,
        inputs,
        outputs,
        minimalGas,
        nonCoinbaseMinGasPrice
      )
      .rightValue

    unsignedTx.fixedOutputs.length is 8
    unsignedTx.inputs.head.unlockScript is fromUnlockScript
    unsignedTx.inputs.tail.foreach(_.unlockScript is UnlockScript.SameAsPrevious)

    info("verify change output")
    unsignedTx.fixedOutputs(5).amount is dustUtxoAmount
    unsignedTx.fixedOutputs(5).tokens is AVector(tokenId2 -> U256.One)
    unsignedTx.fixedOutputs(6).amount is dustUtxoAmount
    unsignedTx.fixedOutputs(6).tokens is AVector(tokenId1 -> U256.One)
    unsignedTx
      .fixedOutputs(7)
      .amount is ALPH.oneAlph.subUnsafe(nonCoinbaseMinGasFee).subUnsafe(dustUtxoAmount * 2)
    unsignedTx.fixedOutputs(7).tokens.isEmpty is true
  }

  it should "fail when output has token that doesn't exist in input" in new UnsignedTransactionFixture {
    val tokenId1 = TokenId.hash("tokenId1")
    val tokenId2 = TokenId.hash("tokenId2")

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
      .buildTransferTx(
        fromLockupScript,
        fromUnlockScript,
        inputs,
        outputs,
        minimalGas,
        nonCoinbaseMinGasPrice
      )
      .leftValue is s"New tokens found in outputs: ${Set(tokenId1)}"
  }

  it should "fail without enough tokens" in new UnsignedTransactionFixture {
    val tokenId1 = TokenId.hash("tokenId1")
    val tokenId2 = TokenId.hash("tokenId2")

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
      .buildTransferTx(
        fromLockupScript,
        fromUnlockScript,
        inputs,
        outputs,
        minimalGas,
        nonCoinbaseMinGasPrice
      )
      .leftValue is s"Not enough balance for token $tokenId2"
  }

  it should "fail when outputs doesn't have minimal amount of Alph" in new UnsignedTransactionFixture {
    {
      info("with tokens")
      val tokenId1 = TokenId.hash("tokenId1")
      val tokenId2 = TokenId.hash("tokenId2")

      val inputs = {
        val input1 = input("input1", ALPH.oneAlph, fromLockupScript, (tokenId2, U256.unsafe(10)))
        val input2 = input("input2", ALPH.alph(3), fromLockupScript, (tokenId1, U256.unsafe(50)))
        AVector(input1, input2)
      }

      val outputs = {
        val output1 =
          output(LockupScript.p2pkh(toPubKey), ALPH.nanoAlph(900), (tokenId2, U256.unsafe(11)))
        AVector(output1)
      }

      UnsignedTransaction
        .buildTransferTx(
          fromLockupScript,
          fromUnlockScript,
          inputs,
          outputs,
          minimalGas,
          nonCoinbaseMinGasPrice
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
        val output1 = output(LockupScript.p2pkh(toPubKey), ALPH.nanoAlph(900))
        AVector(output1)
      }

      UnsignedTransaction
        .buildTransferTx(
          fromLockupScript,
          fromUnlockScript,
          inputs,
          outputs,
          minimalGas,
          nonCoinbaseMinGasPrice
        )
        .leftValue is "Not enough ALPH for transaction output"
    }
  }

  it should "fail when change output doesn't have minimal amount of Alph" in new UnsignedTransactionFixture {
    {
      info("with tokens")
      val tokenId1 = TokenId.hash("tokenId1")
      val tokenId2 = TokenId.hash("tokenId2")

      val inputs = {
        val input1Amount =
          nonCoinbaseMinGasFee.addUnsafe(dustUtxoAmount).subUnsafe(1)
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
          (tokenId2, U256.unsafe(9))
        )
        AVector(output1, output2)
      }

      UnsignedTransaction
        .buildTransferTx(
          fromLockupScript,
          fromUnlockScript,
          inputs,
          outputs,
          minimalGas,
          nonCoinbaseMinGasPrice
        )
        .leftValue is "Not enough ALPH for token change output, expected 2000000000000000, got 999999999999999"
    }

    {
      info("without tokens")
      val inputs = {
        val input1Amount =
          nonCoinbaseMinGasFee.addUnsafe(dustUtxoAmount).subUnsafe(1)
        val input1 = input("input1", input1Amount, fromLockupScript)
        val input2 = input("input2", ALPH.alph(3), fromLockupScript)
        AVector(input1, input2)
      }

      val outputs = {
        val output1 = output(LockupScript.p2pkh(toPubKey), ALPH.oneAlph)
        val output2 = output(LockupScript.p2pkh(toPubKey), ALPH.alph(2))
        AVector(output1, output2)
      }

      UnsignedTransaction
        .buildTransferTx(
          fromLockupScript,
          fromUnlockScript,
          inputs,
          outputs,
          minimalGas,
          nonCoinbaseMinGasPrice
        )
        .leftValue is "Not enough ALPH for ALPH change output, expected 1000000000000000, got 999999999999999"
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
      .buildTransferTx(
        fromLockupScript,
        fromUnlockScript,
        inputs,
        outputs,
        minimalGas,
        nonCoinbaseMinGasPrice
      )
      .leftValue is "Inputs not unique"
  }

  it should "fail when there are tokens with zero value in the transaction output" in new UnsignedTransactionFixture {
    val tokenId1 = TokenId.hash("tokenId1")
    val tokenId2 = TokenId.hash("tokenId2")
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
      .buildTransferTx(
        fromLockupScript,
        fromUnlockScript,
        inputs,
        outputs,
        minimalGas,
        nonCoinbaseMinGasPrice
      )
      .leftValue is "Value is Zero for one or many tokens in the transaction output"
  }

  it should "estimate gas for sweep all tx" in new FlowFixture {
    val txValidation = TxValidation.build
    def test(inputNum: Int, numOfTxs: Int) = {
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
          None,
          keyManager(output.lockupScript).publicKey,
          output.lockupScript,
          None,
          None,
          nonCoinbaseMinGasPrice,
          None,
          defaultUtxoLimit
        )
        .rightValue
        .rightValue
      unsignedTxs.length is numOfTxs
      unsignedTxs.foreach { unsignedTx =>
        unsignedTx.fixedOutputs.length is 1
        unsignedTx.gasAmount is GasEstimation.sweepAddress(inputNum, 1)
        val sweepTx = Transaction.from(unsignedTx, keyManager(output.lockupScript))
        txValidation.validateTxOnlyForTest(sweepTx, blockflow, None) isE ()
      }
    }

    test(1, 0)
    (2 to 10).foreach(test(_, 1))
  }

  trait SweepAlphFixture extends FlowFixture {
    lazy val isConsolidation         = Random.nextBoolean()
    lazy val chainIndex              = ChainIndex.unsafe(0, 0)
    lazy val (privateKey, publicKey) = chainIndex.from.generateKey
    lazy val fromLockupScript        = LockupScript.p2pkh(publicKey)
    lazy val fromUnlockScript        = UnlockScript.p2pkh(publicKey)
    lazy val toLockupScript = {
      if (isConsolidation) {
        fromLockupScript
      } else {
        LockupScript.p2pkh(chainIndex.to.generateKey._2)
      }
    }
    lazy val txValidation = TxValidation.build

    private def transferFromGenesisAddress(numOfOutputs: Int, amountPerUtxo: => U256) = {
      val (genesisPrivKey, genesisPubKey, _) = genesisKeys(chainIndex.from.value)
      val outputInfos = AVector.fill(numOfOutputs)(
        TxOutputInfo(fromLockupScript, amountPerUtxo, AVector.empty, None)
      )
      val unsignedTx = blockFlow
        .transfer(
          genesisPubKey,
          outputInfos,
          None,
          nonCoinbaseMinGasPrice,
          Int.MaxValue,
          ExtraUtxosInfo.empty
        )
        .rightValue
        .rightValue
      val transaction = Transaction.from(unsignedTx, genesisPrivKey)
      val block       = mineWithTxs(blockFlow, chainIndex, AVector(transaction))
      addAndCheck(blockFlow, block)
    }

    def getAlphOutputs(amounts: AVector[U256]): AVector[AssetOutputInfo] = {
      var index = 0
      val genAmount = () => {
        val amount = amounts(index)
        index += 1
        amount
      }
      getAlphOutputs(amounts.length, genAmount()).sortBy(_.output.amount)
    }

    def getAlphOutputs(
        numOfUtxos: Int,
        amountPerUtxo: => U256 = ALPH.oneAlph
    ): AVector[AssetOutputInfo] = {
      val prevAllUtxos  = blockFlow.getUsableUtxos(fromLockupScript, Int.MaxValue).rightValue
      val prevAlphUtxos = prevAllUtxos.filter(_.output.tokens.isEmpty)
      val txNum         = numOfUtxos / 200
      val remainder     = numOfUtxos % 200
      (0 until txNum).foreach(_ => transferFromGenesisAddress(200, amountPerUtxo))
      if (remainder != 0) transferFromGenesisAddress(remainder, amountPerUtxo)
      val allUtxos  = blockFlow.getUsableUtxos(fromLockupScript, Int.MaxValue).rightValue
      val alphUtxos = allUtxos.filter(_.output.tokens.isEmpty)
      val utxos     = alphUtxos.filter(utxo => !prevAlphUtxos.exists(_.ref == utxo.ref))
      utxos.length is numOfUtxos
      utxos
    }

    def checkAndSignTx(unsignedTx: UnsignedTransaction): Transaction = {
      unsignedTx.fixedOutputs.foreach(_.lockupScript is toLockupScript)
      unsignedTx.gasAmount is GasEstimation
        .estimateWithInputScript(
          fromUnlockScript,
          unsignedTx.inputs.length,
          unsignedTx.fixedOutputs.length,
          AssetScriptGasEstimator.NotImplemented
        )
        .rightValue
      val sweepTx = Transaction.from(unsignedTx, privateKey)
      txValidation.validateTxOnlyForTest(sweepTx, blockFlow, None) isE ()
      sweepTx
    }

    def testSweepALPH(
        utxos: AVector[AssetOutputInfo],
        gasOpt: Option[GasBox] = None,
        lockTimeOpt: Option[TimeStamp] = None
    ) = {
      val (unsignedTxs, _) = blockFlow.buildSweepAlphTxs(
        fromLockupScript,
        fromUnlockScript,
        toLockupScript,
        lockTimeOpt,
        utxos,
        gasOpt,
        nonCoinbaseMinGasPrice
      )
      unsignedTxs.map { unsignedTx =>
        unsignedTx.fixedOutputs.length is 1
        checkAndSignTx(unsignedTx)
      }
    }

    def getBalances(lockupScript: LockupScript.Asset): (U256, AVector[(TokenId, U256)]) = {
      val (alph, lockedAlph, tokens, lockedTokens, _) =
        blockFlow.getBalance(lockupScript, Int.MaxValue, true).rightValue
      lockedAlph is U256.Zero
      lockedTokens.isEmpty is true
      (alph, tokens)
    }

    def submitSweepTxs(txs: AVector[Transaction]) = {
      txs.fold(U256.Zero) { case (acc, tx) =>
        blockFlow.grandPool.add(chainIndex, tx.toTemplate, TimeStamp.now())
        val block = mineFromMemPool(blockFlow, chainIndex)
        block.nonCoinbase is AVector(tx)
        addAndCheck(blockFlow, block)
        acc.addUnsafe(tx.gasFeeUnsafe)
      }
    }

    def submitSweepTxsAndCheckBalances(txs: AVector[Transaction]) = {
      val (alph0, tokens0) = getBalances(fromLockupScript)
      val totalGasFee      = submitSweepTxs(txs)
      val (alph1, tokens1) = getBalances(toLockupScript)
      alph0.subUnsafe(totalGasFee) is alph1
      tokens0.sortBy(_._1) is tokens1.sortBy(_._1)
      if (!isConsolidation) {
        blockFlow.getUsableUtxos(fromLockupScript, Int.MaxValue).rightValue.isEmpty is true
      }
    }

    def sweep() = {
      blockFlow
        .sweepAddress(
          None,
          publicKey,
          toLockupScript,
          None,
          None,
          nonCoinbaseMinGasPrice,
          None,
          Int.MaxValue
        )
        .rightValue
    }
  }

  it should "sweep ALPH" in new SweepAlphFixture {
    override lazy val isConsolidation = false
    val numOfUtxos                    = Random.between(1, 1000)
    val utxos                         = getAlphOutputs(numOfUtxos)
    utxos.length is numOfUtxos
    val txs = testSweepALPH(utxos)
    txs.length is (utxos.length - 1) / ALPH.MaxTxInputNum + 1
    submitSweepTxsAndCheckBalances(txs)
  }

  it should "consolidate ALPH" in new SweepAlphFixture {
    override lazy val isConsolidation = true
    val numOfUtxos                    = Random.between(1, 1000)
    val utxos                         = getAlphOutputs(numOfUtxos)
    utxos.length is numOfUtxos
    val txs = testSweepALPH(utxos)
    val numOfTxs =
      numOfUtxos / ALPH.MaxTxInputNum + (if (numOfUtxos % ALPH.MaxTxInputNum > 1) 1 else 0)
    txs.length is numOfTxs
    submitSweepTxsAndCheckBalances(txs)
  }

  it should "sweep ALPH by ascending order" in new SweepAlphFixture {
    val numOfUtxos  = 300
    val alphAmounts = AVector.from(1 to numOfUtxos).map(dustUtxoAmount.mulUnsafe(_))
    val utxos       = getAlphOutputs(alphAmounts)
    utxos.length is numOfUtxos

    utxos.foreachWithIndex { case (utxo, index) =>
      utxo.output.amount is dustUtxoAmount.mulUnsafe(index + 1)
    }
    val txs = testSweepALPH(utxos)
    txs.length is 2
    (0 until ALPH.MaxTxInputNum).foreach { index =>
      utxos(index).ref is txs.head.unsigned.inputs(index).outputRef
    }
    (ALPH.MaxTxInputNum until numOfUtxos).foreach { index =>
      utxos(index).ref is txs.last.unsigned.inputs(index - ALPH.MaxTxInputNum).outputRef
    }
    submitSweepTxsAndCheckBalances(txs)
  }

  it should "not create txs if there is only one utxo left when consolidating" in new SweepAlphFixture {
    override lazy val isConsolidation = true

    val utxos0   = getAlphOutputs(1)
    val lockTime = Some(TimeStamp.zero)
    utxos0.length is 1
    testSweepALPH(utxos0).length is 0
    testSweepALPH(utxos0, None, lockTime).length is 1

    val utxos1 = getAlphOutputs(ALPH.MaxTxInputNum - 1) ++ utxos0
    utxos1.length is ALPH.MaxTxInputNum
    testSweepALPH(utxos1).length is 1
    testSweepALPH(utxos1, None, lockTime).length is 1

    val utxos2 = getAlphOutputs(1) ++ utxos1
    utxos2.length is ALPH.MaxTxInputNum + 1
    testSweepALPH(utxos2).length is 1
    testSweepALPH(utxos2, None, lockTime).length is 2

    val utxos3 = getAlphOutputs(1) ++ utxos2
    utxos3.length is ALPH.MaxTxInputNum + 2
    val txs = testSweepALPH(utxos3, None, lockTime)
    txs.length is 2
    submitSweepTxsAndCheckBalances(txs)
  }

  it should "return an error if there is not enough ALPH for transaction output" in new SweepAlphFixture {
    val utxos = getAlphOutputs(2, dustUtxoAmount)
    blockFlow
      .tryBuildSweepAlphTx(
        fromLockupScript,
        fromUnlockScript,
        toLockupScript,
        None,
        utxos,
        None,
        GasPrice(nonCoinbaseMinGasPrice.value + 1)
      )
      .leftValue is "Not enough ALPH for transaction output in sweeping"
  }

  it should "return an error if the specified gas is not enough: sweep ALPH" in new SweepAlphFixture {
    val utxos = getAlphOutputs(7)
    blockFlow
      .tryBuildSweepAlphTx(
        fromLockupScript,
        fromUnlockScript,
        toLockupScript,
        None,
        utxos,
        Some(minimalGas),
        nonCoinbaseMinGasPrice
      )
      .leftValue
      .startsWith("The specified gas amount is not enough") is true
  }

  it should "return an error if the sweep of ALPH fails" in new SweepAlphFixture {
    getAlphOutputs(2, dustUtxoAmount)
    sweep().leftValue is "Not enough ALPH for transaction output"
  }

  trait SweepTokenFixture extends SweepAlphFixture {
    def getTokenOutputs(
        numOfTokens: Int,
        numOfUtxosPerToken: Int,
        amountPerUtxo: U256 = U256.One
    ): AVector[AssetOutputInfo] = {
      val prevAllUtxos   = blockFlow.getUsableUtxos(fromLockupScript, Int.MaxValue).rightValue
      val prevTokenUtxos = prevAllUtxos.filter(_.output.tokens.nonEmpty)
      val tokenOutputs = AVector.from(0 until numOfTokens).flatMap { _ =>
        val tokenId = TokenId.random
        AVector.from(0 until numOfUtxosPerToken).map { _ =>
          AssetOutput(
            dustUtxoAmount,
            fromLockupScript,
            TimeStamp.zero,
            AVector((tokenId, amountPerUtxo)),
            ByteString.empty
          )
        }
      }
      val tx = Transaction.from(AVector.empty[TxInput], tokenOutputs, AVector.empty[Signature])
      val worldState = blockFlow.getBestCachedWorldState(chainIndex.from).rightValue
      val block      = emptyBlock(blockFlow, chainIndex)
      blockFlow.addAndUpdateView(
        block.copy(transactions = tx +: block.transactions),
        Some(worldState)
      ) isE ()
      val allUtxos   = blockFlow.getUsableUtxos(fromLockupScript, Int.MaxValue).rightValue
      val tokenUtxos = allUtxos.filter(_.output.tokens.nonEmpty)
      val utxos      = tokenUtxos.filter(utxo => !prevTokenUtxos.exists(_.ref == utxo.ref))
      utxos.map(_.output).toSet is tokenOutputs.map(_.copy(lockTime = block.timestamp)).toSet
      utxos.length is numOfTokens * numOfUtxosPerToken
      utxos
    }

    def testSweepToken(
        tokenUtxos: AVector[AssetOutputInfo],
        alphUtxos: AVector[AssetOutputInfo],
        numOfTxs: Int,
        gasOpt: Option[GasBox] = None,
        lockTimeOpt: Option[TimeStamp] = None,
        checker: AVector[AssetOutputInfo] => Assertion = _ => Succeeded
    ) = {
      val (sweepTokenTxs, restAlphUtxos, _) = blockFlow.buildSweepTokenTxs(
        fromLockupScript,
        fromUnlockScript,
        toLockupScript,
        lockTimeOpt,
        tokenUtxos,
        alphUtxos,
        gasOpt,
        nonCoinbaseMinGasPrice
      )
      checker(restAlphUtxos)
      sweepTokenTxs.length is numOfTxs
      val sweepAlphTxs = if (restAlphUtxos.nonEmpty) {
        testSweepALPH(restAlphUtxos, gasOpt, lockTimeOpt)
      } else {
        AVector.empty
      }
      sweepTokenTxs.map(checkAndSignTx) ++ sweepAlphTxs
    }
  }

  it should "sweep one token with multiple outputs into one tx" in new SweepTokenFixture {
    val tokenOutputs = getTokenOutputs(1, 100)
    val txs          = testSweepToken(tokenOutputs, AVector.empty, 1)
    submitSweepTxsAndCheckBalances(txs)
  }

  it should "sweep one token with multiple outputs into multiple txs" in new SweepTokenFixture {
    val tokenOutputs = getTokenOutputs(1, 300)
    val txs          = testSweepToken(tokenOutputs, AVector.empty, 3)
    submitSweepTxsAndCheckBalances(txs)
  }

  it should "sweep utxos with the same token into one transaction as much as possible" in new SweepTokenFixture {
    val tokenOutputs = getTokenOutputs(2, 200)
    val txs          = testSweepToken(tokenOutputs.shuffle(), AVector.empty, 4)
    val outputRefs   = tokenOutputs.map(utxo => (utxo.ref, utxo.output.tokens.head._1)).toSeq.toMap
    txs.take(3).foreach(_.unsigned.inputs.length is ALPH.MaxTxInputNum / 2)
    txs.last.unsigned.inputs.length is 16
    txs(0).unsigned.inputs.map(input => outputRefs(input.outputRef)).toSet.size is 1
    txs(1).unsigned.inputs.map(input => outputRefs(input.outputRef)).toSet.size is 2
    txs(2).unsigned.inputs.map(input => outputRefs(input.outputRef)).toSet.size is 1
    txs(3).unsigned.inputs.map(input => outputRefs(input.outputRef)).toSet.size is 1
    submitSweepTxsAndCheckBalances(txs)
  }

  it should "sweep multiple tokens into one tx" in new SweepTokenFixture {
    val tokenOutputs = getTokenOutputs(5, 20)
    val txs          = testSweepToken(tokenOutputs, AVector.empty, 1)
    submitSweepTxsAndCheckBalances(txs)
  }

  it should "sweep multiple tokens into multiple txs" in new SweepTokenFixture {
    val tokenOutputs = getTokenOutputs(15, 20)
    val txs          = testSweepToken(tokenOutputs, AVector.empty, 3)
    submitSweepTxsAndCheckBalances(txs)
  }

  it should "not consolidate tokens that have only one utxo" in new SweepTokenFixture {
    override lazy val isConsolidation = true

    val lockTime        = Some(TimeStamp.zero)
    val tokenOutputs0   = getTokenOutputs(100, 1, U256.One)
    val tokenOutputs1   = getTokenOutputs(50, 2, U256.One)
    val allTokenOutputs = tokenOutputs0 ++ tokenOutputs1
    val txs0            = testSweepToken(allTokenOutputs, AVector.empty, 1)
    tokenOutputs0.foreach { output =>
      txs0.exists(_.unsigned.inputs.exists(_.outputRef == output.ref)) is false
    }
    tokenOutputs1.foreach { output =>
      txs0.exists(_.unsigned.inputs.exists(_.outputRef == output.ref)) is true
    }

    val alphOutputs = getAlphOutputs(2)
    val txs1        = testSweepToken(allTokenOutputs, alphOutputs, 2, None, lockTime)
    tokenOutputs0.foreach { output =>
      txs1.exists(_.unsigned.inputs.exists(_.outputRef == output.ref)) is true
    }
    tokenOutputs1.foreach { output =>
      txs1.exists(_.unsigned.inputs.exists(_.outputRef == output.ref)) is true
    }
    submitSweepTxsAndCheckBalances(txs1)
  }

  it should "return empty txs if all tokens have only one utxo when consolidating" in new SweepTokenFixture {
    override lazy val isConsolidation = true
    val tokenOutputs                  = getTokenOutputs(100, 1, U256.One)
    val alphOutputs                   = getAlphOutputs(1)
    testSweepToken(tokenOutputs, alphOutputs, 0)
  }

  it should "not use ALPH utxos if token utxos can cover the gas fee" in new SweepTokenFixture {
    val tokenOutputs = getTokenOutputs(3, 40)
    val alphOutputs  = getAlphOutputs(2)
    val txs          = testSweepToken(tokenOutputs, alphOutputs, 1, None, None, _ is alphOutputs)
    submitSweepTxsAndCheckBalances(txs)
  }

  it should "use ALPH utxos if token utxos cannot cover the gas fee" in new SweepTokenFixture {
    override lazy val isConsolidation = false
    val tokenOutputs                  = getTokenOutputs(500, 1)
    val alphOutputs                   = getAlphOutputs(4)
    val txs = testSweepToken(tokenOutputs, alphOutputs, 4, None, None, _.isEmpty is true)
    submitSweepTxsAndCheckBalances(txs)
  }

  it should "return an error if there is not enough ALPH for gas fee" in new SweepTokenFixture {
    val tokenOutputs = getTokenOutputs(2, 2)
    blockFlow
      .tryBuildSweepTokenTx(
        fromLockupScript,
        fromUnlockScript,
        toLockupScript,
        None,
        tokenOutputs,
        AVector.empty,
        None,
        nonCoinbaseMinGasPrice
      )
      .leftValue is "Not enough ALPH for gas fee in sweeping"
  }

  it should "return an error if the specified gas is not enough: sweep tokens" in new SweepTokenFixture {
    val tokenOutputs = getTokenOutputs(3, 2)
    val alphOutputs  = getAlphOutputs(1)
    blockFlow
      .tryBuildSweepTokenTx(
        fromLockupScript,
        fromUnlockScript,
        toLockupScript,
        None,
        tokenOutputs,
        alphOutputs,
        Some(minimalGas),
        nonCoinbaseMinGasPrice
      )
      .leftValue
      .startsWith("The specified gas amount is not enough") is true
  }

  it should "fall back to the descending order when ascending order doesn't work" in new SweepTokenFixture {
    val tokenOutputs = getTokenOutputs(3, 2)
    val alphAmounts  = AVector(dustUtxoAmount, dustUtxoAmount, dustUtxoAmount, ALPH.oneAlph)
    val alphOutputs  = getAlphOutputs(alphAmounts)
    alphOutputs.map(_.output.amount) is alphAmounts

    val gas = GasEstimation.estimateWithSameP2PKHInputs(7, 4)
    gas is GasBox.unsafe(35060)
    blockFlow
      .tryBuildSweepTokenTx(
        fromLockupScript,
        fromUnlockScript,
        toLockupScript,
        None,
        tokenOutputs,
        alphOutputs,
        Some(gas),
        nonCoinbaseMinGasPrice
      )
      .isLeft is true

    val txs = testSweepToken(
      tokenOutputs,
      alphOutputs,
      1,
      Some(gas),
      None,
      _.map(_.output.amount) is alphAmounts.take(3)
    )
    submitSweepTxsAndCheckBalances(txs)
  }

  it should "test the extreme case" in new SweepTokenFixture {
    override lazy val isConsolidation = false
    ALPH.MaxTxInputNum / 2 is 128
    val tokenOutputs = getTokenOutputs(128, 1)
    val alphOutputs  = getAlphOutputs(128, dustUtxoAmount)
    val txs          = testSweepToken(tokenOutputs, alphOutputs, 1)
    submitSweepTxsAndCheckBalances(txs)
  }

  it should "complete the sweep in multiple rounds" in new SweepTokenFixture {
    override lazy val isConsolidation = true
    getTokenOutputs(2, 200)
    getAlphOutputs(ALPH.MaxTxInputNum + 1, dustUtxoAmount)
    val (alph0, tokens0) = getBalances(fromLockupScript)

    val txs0 = sweep().rightValue
    txs0.length is 5
    val gasFee0 = submitSweepTxs(txs0.map(checkAndSignTx))
    val txs1    = sweep().rightValue
    txs1.length is 2
    val gasFee1     = submitSweepTxs(txs1.map(checkAndSignTx))
    val totalGasFee = gasFee0.addUnsafe(gasFee1)

    val (alph1, tokens1) = getBalances(toLockupScript)
    alph0.subUnsafe(totalGasFee) is alph1
    tokens0.sortBy(_._1) is tokens1.sortBy(_._1)
  }

  it should "return an error if the sweep of token fails" in new SweepTokenFixture {
    getTokenOutputs(1, 2)
    getAlphOutputs(2, dustUtxoAmount)
    sweep().leftValue is "Not enough ALPH for gas fee in sweeping"
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

    val (balance, lockedBalance, _, _, numOfUtxos) =
      blockFlow.getBalance(output.lockupScript, Int.MaxValue, true).rightValue
    balance is U256.unsafe(outputs.sumBy(_.amount.toBigInt))
    lockedBalance is 0
    numOfUtxos is n
  }

  it should "get all available utxos" in new LargeUtxos {
    val fetchedUtxos = blockFlow.getUsableUtxos(this.output.lockupScript, n).rightValue
    fetchedUtxos.length is n
  }

  it should "transfer with large amount of UTXOs with provided gas" in new LargeUtxos {
    val txValidation = TxValidation.build
    val unsignedTx0 = blockFlow
      .transfer(
        keyManager(this.output.lockupScript).publicKey,
        this.output.lockupScript,
        None,
        ALPH.alph((ALPH.MaxTxInputNum - 1).toLong),
        Some(GasBox.unsafe(600000)),
        nonCoinbaseMinGasPrice,
        defaultUtxoLimit
      )
      .rightValue
      .rightValue
    val tx0 = Transaction.from(unsignedTx0, keyManager(this.output.lockupScript))
    tx0.unsigned.inputs.length is ALPH.MaxTxInputNum
    tx0.inputSignatures.length is 1
    txValidation.validateTxOnlyForTest(tx0, blockFlow, None) isE ()

    blockFlow
      .transfer(
        keyManager(this.output.lockupScript).publicKey,
        this.output.lockupScript,
        None,
        ALPH.alph(ALPH.MaxTxInputNum.toLong),
        Some(GasBox.unsafe(600000)),
        nonCoinbaseMinGasPrice,
        defaultUtxoLimit
      )
      .rightValue
      .leftValue is "Too many inputs for the transfer, consider to reduce the amount to send, or use the `sweep-address` endpoint to consolidate the inputs first"
  }

  it should "transfer with large amount of UTXOs with estimated gas" in new LargeUtxos {
    val maxP2PKHInputsAllowedByGas = 256

    info("With provided Utxos")

    val availableUtxos = blockFlow
      .getUTXOs(this.output.lockupScript, Int.MaxValue, true)
      .rightValue
      .asUnsafe[AssetOutputInfo]
    val availableInputs = availableUtxos.map(_.ref)
    val outputInfos = AVector.fill(255)(
      TxOutputInfo(
        this.output.lockupScript,
        ALPH.alph(1),
        AVector.empty,
        None
      )
    )

    val tx0 = blockFlow
      .transfer(
        keyManager(this.output.lockupScript).publicKey,
        availableInputs.take(maxP2PKHInputsAllowedByGas),
        outputInfos,
        None,
        nonCoinbaseMinGasPrice
      )
      .rightValue
      .rightValue
    tx0.gasAmount is GasBox.unsafe(1667060)
    tx0.inputs.length is maxP2PKHInputsAllowedByGas
    tx0.fixedOutputs.length is 256

    info("Without provided Utxos")

    val tx1 = blockFlow
      .transfer(
        keyManager(this.output.lockupScript).publicKey,
        outputInfos,
        None,
        nonCoinbaseMinGasPrice,
        defaultUtxoLimit,
        ExtraUtxosInfo.empty
      )
      .rightValue
      .rightValue
    tx1.gasAmount is GasBox.unsafe(1667060)
    tx1.inputs.length is maxP2PKHInputsAllowedByGas
    tx1.fixedOutputs.length is 256
  }

  it should "sweep as much as we can" in new LargeUtxos {
    val txValidation = TxValidation.build

    {
      info("Sweep all UTXOs")
      val unsignedTxs = blockFlow
        .sweepAddress(
          None,
          keyManager(this.output.lockupScript).publicKey,
          this.output.lockupScript,
          None,
          None,
          nonCoinbaseMinGasPrice,
          None,
          Int.MaxValue
        )
        .rightValue
        .rightValue

      unsignedTxs.length is 3

      unsignedTxs.foreach { unsignedTx =>
        val sweepTx = Transaction.from(unsignedTx, keyManager(this.output.lockupScript))
        txValidation.validateTxOnlyForTest(sweepTx, blockFlow, None) isE ()
      }
    }

    {
      info("Sweep all UTXOs with less than 1 ALPH")
      val unsignedTxs = blockFlow
        .sweepAddress(
          None,
          keyManager(this.output.lockupScript).publicKey,
          this.output.lockupScript,
          None,
          None,
          nonCoinbaseMinGasPrice,
          Some(ALPH.oneAlph),
          Int.MaxValue
        )
        .rightValue
        .rightValue
      unsignedTxs.length is 0
    }
  }

  it should "calculate balances correctly" in new TxGenerators with AlephiumConfigFixture {
    val now          = TimeStamp.now()
    val timestampGen = Gen.oneOf(Seq(TimeStamp.zero, now.plusHoursUnsafe(1)))
    val assetOutputsGen = Gen
      .listOf(
        assetOutputGen(GroupIndex.unsafe(0))(
          timestampGen = timestampGen
        )
      )
      .map(AVector.from)

    def getTokenBalances(assetOutputs: AVector[AssetOutput]): AVector[(TokenId, U256)] = {
      AVector.from(
        assetOutputs
          .flatMap(_.tokens)
          .groupBy(_._1)
          .map { case (tokenId, tokensPerId) =>
            (tokenId, U256.unsafe(tokensPerId.sumBy(_._2.v)))
          }
      )
    }

    forAll(assetOutputsGen) { assetOutputs =>
      val (attoAlphBalance, attoAlphLockedBalance, tokenBalances, lockedTokenBalances) =
        TxUtils.getBalance(assetOutputs.as[TxOutput])

      attoAlphBalance is U256.unsafe(assetOutputs.sumBy(_.amount.v))
      attoAlphLockedBalance is U256.unsafe(assetOutputs.filter(_.lockTime > now).sumBy(_.amount.v))

      val expectedTokenBalances       = getTokenBalances(assetOutputs)
      val expectedLockedTokenBalances = getTokenBalances(assetOutputs.filter(_.lockTime > now))
      tokenBalances.sorted is expectedTokenBalances.sorted
      lockedTokenBalances.sorted is expectedLockedTokenBalances.sorted
    }
  }

  it should "get utxos for asset address" in new FlowFixture {
    val chainIndex   = ChainIndex.unsafe(0, 0)
    val block        = transfer(blockFlow, chainIndex)
    val transferTx   = block.nonCoinbase.head.toTemplate
    val grandPool    = blockFlow.getGrandPool()
    val mempool      = blockFlow.getMemPool(chainIndex)
    val lockupScript = getGenesisLockupScript(chainIndex)

    grandPool.add(chainIndex, transferTx, TimeStamp.now())
    mempool.contains(transferTx) is true

    blockFlow
      .getUTXOs(lockupScript, Int.MaxValue, true)
      .rightValue
      .map(_.output.asInstanceOf[AssetOutput]) is transferTx.unsigned.fixedOutputs.tail
    blockFlow
      .getUTXOs(lockupScript, Int.MaxValue, false)
      .rightValue
      .map(_.ref.asInstanceOf[AssetOutputRef]) is transferTx.unsigned.inputs.map(_.outputRef)

    addAndCheck(blockFlow, block)
    val utxos = transferTx.unsigned.fixedOutputs.tail.map(_.copy(lockTime = block.timestamp))
    blockFlow
      .getUTXOs(lockupScript, Int.MaxValue, true)
      .rightValue
      .map(_.output.asInstanceOf[AssetOutput]) is utxos
    blockFlow
      .getUTXOs(lockupScript, Int.MaxValue, false)
      .rightValue
      .map(_.output.asInstanceOf[AssetOutput]) is utxos
  }

  trait ContractFixture extends FlowFixture {
    val contractCode =
      s"""
         |Contract Foo() {
         |  fn foo() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
    val (contractId, contractOutputRef, block) =
      createContract(
        contractCode,
        AVector.empty,
        AVector.empty,
        tokenIssuanceInfo = Some(TokenIssuance.Info(1))
      )
    val contractOutputScript = LockupScript.p2c(contractId)
  }

  it should "get balance for contract address" in new ContractFixture {
    val (attoAlphBalance, attoAlphLockedBalance, tokenBalances, tokenLockedBalances, utxosNum) =
      blockFlow.getBalance(contractOutputScript, Int.MaxValue, true).rightValue
    attoAlphBalance is minimalAlphInContract
    attoAlphLockedBalance is U256.Zero
    tokenBalances is AVector(TokenId.from(contractId) -> U256.unsafe(1))
    tokenLockedBalances.length is 0
    utxosNum is 1
  }

  it should "get UTXOs for contract address" in new ContractFixture {
    val utxos = blockFlow.getUTXOs(contractOutputScript, Int.MaxValue, true).rightValue
    val utxo  = utxos.head.asInstanceOf[FlowUtils.ContractOutputInfo]
    utxos.length is 1
    utxo.ref is contractOutputRef
    utxo.output.lockupScript is contractOutputScript
    utxo.output.amount is minimalAlphInContract
    utxo.output.tokens is AVector(TokenId.from(contractId) -> U256.unsafe(1))
  }

  it should "transfer to Schnorr addrss" in new FlowFixture {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))

    val chainIndex                        = ChainIndex.unsafe(0, 0)
    val (genesisPriKey, genesisPubKey, _) = genesisKeys(0)

    val (priKey, pubKey) = BIP340Schnorr.generatePriPub()
    val schnorrAddress   = SchnorrAddress(pubKey)

    val block0 = transfer(
      blockFlow,
      genesisPriKey,
      schnorrAddress.lockupScript,
      AVector.empty[(TokenId, U256)],
      ALPH.alph(2)
    )
    addAndCheck(blockFlow, block0)

    // In case, the first transfer is a cross-group transaction
    val confirmBlock = emptyBlock(blockFlow, chainIndex)
    addAndCheck(blockFlow, confirmBlock)

    val (balance, _, _, _, utxoNum) =
      blockFlow.getBalance(schnorrAddress.lockupScript, Int.MaxValue, true).rightValue
    balance is ALPH.alph(2)
    utxoNum is 1

    val unsignedTx = blockFlow
      .transfer(
        None,
        schnorrAddress.lockupScript,
        schnorrAddress.unlockScript,
        AVector(TxOutputInfo(LockupScript.p2pkh(genesisPubKey), ALPH.oneAlph, AVector.empty, None)),
        None,
        nonCoinbaseMinGasPrice,
        Int.MaxValue,
        ExtraUtxosInfo.empty
      )
      .rightValue
      .rightValue

    val signature = BIP340Schnorr.sign(unsignedTx.id.bytes, priKey)
    val tx = TransactionTemplate(
      unsignedTx,
      AVector(Signature.unsafe(signature.bytes)),
      scriptSignatures = AVector.empty
    )
    TxValidation.build.validateMempoolTxTemplate(tx, blockFlow) isE ()
    blockFlow
      .getGrandPool()
      .add(chainIndex, tx, TimeStamp.now()) is MemPool.AddedToMemPool
  }

  "TxUtils.transferMultiInputs" should "transfer multi inputs" in new MultiInputTransactionFixture {
    checkMultiInputTx(1, 1)
    checkMultiInputTx(2, 1)
    checkMultiInputTx(3, 1)
    checkMultiInputTx(5, 1)
    checkMultiInputTx(5, 4)
    checkMultiInputTx(5, 30)
    checkMultiInputTx(30, 1)
    checkMultiInputTx(10, 10)
    checkMultiInputTx(20, 30)
  }

  it should "transfer multi inputs with different nb of utxos per input" in new MultiInputTransactionFixture {
    val nbOfInputs                = 4
    val nbOfOutputs               = 4
    val (publicKeys, privateKeys) = genKeys(nbOfInputs)

    publicKeys.zipWithIndex.foreach { case (pubKey, i) =>
      def block = buildBlock(pubKey, amount + 1)
      // each address will get different number of utxos
      (0 to i).foreach { _ =>
        addAndCheck(blockFlow, block)
      }
    }

    // each input will use different number of utxos
    val inputs = publicKeys.mapWithIndex { case (pubKey, i) =>
      val amnt = (i + 1) * amount
      buildInputData(pubKey, amnt)
    }

    val totalAmount = (1 to nbOfInputs).map { i =>
      i * amount
    }.sum

    val outputs = buildOutputs(nbOfOutputs, totalAmount)

    val utx = blockFlow
      .transferMultiInputs(
        inputs,
        outputs,
        nonCoinbaseMinGasPrice,
        Int.MaxValue,
        None
      )
      .rightValue
      .rightValue
    utx.inputs.length is 10
    var inputIndex = 0
    publicKeys.foreachWithIndex { case (pubKey, index) =>
      val inputSize     = index + 1
      val inputsFromKey = utx.inputs.slice(inputIndex, inputIndex + inputSize)
      inputsFromKey.head.unlockScript is UnlockScript.p2pkh(pubKey)
      inputsFromKey.tail.foreach(_.unlockScript is UnlockScript.SameAsPrevious)
      inputIndex += inputSize
    }

    (utx.gasAmount > GasEstimation.estimateWithSameP2PKHInputs(
      nbOfInputs,
      nbOfOutputs + nbOfInputs
    )) is true
    (utx.gasAmount > GasEstimation.estimateWithSameP2PKHInputs(
      (1 to nbOfInputs).sum - 1,
      nbOfOutputs + nbOfInputs
    )) is true

    (utx.gasAmount <= estimateWithDifferentP2PKHInputs(
      (1 to nbOfInputs).sum,
      nbOfOutputs + nbOfInputs
    )) is true

    val changeOutputs = utx.fixedOutputs.drop(nbOfOutputs).map(_.amount)

    // Each input will pay different fee, so each change output is different
    changeOutputs.toSeq.distinct.length is changeOutputs.length

    validateSubmit(utx, privateKeys)
  }

  it should "transfer multi inputs with gas defined" in new MultiInputTransactionFixture {
    val nbOfInputs                = 4
    val nbOfOutputs               = 1
    val (publicKeys, privateKeys) = genKeys(nbOfInputs)
    val amountPerBlock            = 100L

    publicKeys.foreach { pubKey =>
      val block = buildBlock(pubKey, amountPerBlock)
      addAndCheck(blockFlow, block)
    }

    // We show that we can even define less than the minimal gas, as the overall gas will be enough
    val gas = GasBox.unsafe(minimalGas.value - (minimalGas.value / 4))

    val inputs = publicKeys.mapWithIndex { case (pubKey, i) =>
      buildInputData(pubKey, amount, Some(gas.mulUnsafe(i)))
    }

    val totalAmount = amount * nbOfInputs

    val outputs = buildOutputs(nbOfOutputs, totalAmount)

    val utx = blockFlow
      .transferMultiInputs(
        inputs,
        outputs,
        nonCoinbaseMinGasPrice,
        Int.MaxValue,
        None
      )
      .rightValue
      .rightValue

    val changeOutputs = utx.fixedOutputs.drop(nbOfOutputs).map(_.amount)

    changeOutputs.zipWithIndex.foreach { case (change, i) =>
      val expected =
        ALPH.alph(amountPerBlock - amount) - nonCoinbaseMinGasPrice * gas.mulUnsafe(i)
      change is expected
    }

    validateSubmit(utx, privateKeys)
  }

  it should "transfer multi inputs with explicit utxos" in new MultiInputTransactionFixture {
    val nbOfInputs                = 4
    val nbOfOutputs               = 1
    val (publicKeys, privateKeys) = genKeys(nbOfInputs)
    val amountPerBlock            = 100L

    publicKeys.foreach { pubKey =>
      def block = buildBlock(pubKey, amountPerBlock)
      // Force 2 utxos
      addAndCheck(blockFlow, block)
      addAndCheck(blockFlow, block)
    }

    val inputs = publicKeys.mapWithIndex { case (pubKey, i) =>
      if (i == 0) {
        // First input pass all it's utxos, it should be merged
        val availableUtxos = blockFlow
          .getUTXOs(Address.p2pkh(pubKey).lockupScript, Int.MaxValue, true)
          .rightValue
          .asUnsafe[AssetOutputInfo]

        // First pubKey will use both utxos
        buildInputData(pubKey, amountPerBlock + amount, utxos = Some(availableUtxos.map(_.ref)))
      } else {
        buildInputData(pubKey, amount)
      }
    }

    val totalAmount = amountPerBlock + (amount * nbOfInputs)

    val outputs = buildOutputs(nbOfOutputs, totalAmount)

    val utx = blockFlow
      .transferMultiInputs(
        inputs,
        outputs,
        nonCoinbaseMinGasPrice,
        Int.MaxValue,
        None
      )
      .rightValue
      .rightValue

    val gasEstimation = estimateWithDifferentP2PKHInputs(utx.inputs.length, utx.fixedOutputs.length)
    utx.gasAmount <= gasEstimation is true

    utx.inputs.length is nbOfInputs + 1 // for the extra utxos of first pub key

    validateSubmit(utx, privateKeys)
  }

  it should "transfer multi inputs with tokens" in new MultiInputTransactionFixture
    with ContractFixture {

    def test(nbOfInputs: Int, nbOfOutputs: Int, nbOfTokens: Int, nbOfTokenHolders: Int) = {
      val (publicKeys, privateKeys) = genKeys(nbOfInputs)
      val amountPerBlock            = 100L
      val tokenAmount               = 2 * nbOfInputs

      val tokens = AVector.fill(nbOfTokens) {
        val (cId, _, _) = createContract(
          contractCode,
          AVector.empty,
          AVector.empty,
          tokenIssuanceInfo = Some(
            TokenIssuance.Info(
              Val.U256(U256.unsafe(tokenAmount)),
              Some(Address.p2pkh(genesisPubKey).lockupScript)
            )
          )
        )

        (TokenId.from(cId), U256.Two)
      }

      publicKeys.foreach { pubKey =>
        val block = buildBlock(pubKey, amountPerBlock, Some(tokens))
        addAndCheck(blockFlow, block)
      }

      val inputs = publicKeys.mapWithIndex { case (pubKey, i) =>
        // Holders will send only half of each of their tokens
        if (i < nbOfTokenHolders) {
          buildInputData(
            pubKey,
            amount,
            tokens = Some(tokens.map { case (tokenId, _) => (tokenId, U256.One) })
          )
        } else {
          buildInputData(pubKey, amount, tokens = Some(tokens))
        }
      }

      val totalAmount = amount * nbOfInputs

      val outputTokens =
        tokens.map { case (tokenId, _) =>
          (tokenId, U256.unsafe(tokenAmount - nbOfTokenHolders))
        }

      val outputsWithoutTokens = buildOutputs(nbOfOutputs, totalAmount)
      // We send the tokens to the first output
      val outputs =
        outputsWithoutTokens.replace(0, outputsWithoutTokens.head.copy(tokens = outputTokens))

      val utx = blockFlow
        .transferMultiInputs(
          inputs,
          outputs,
          nonCoinbaseMinGasPrice,
          Int.MaxValue,
          None
        )
        .rightValue
        .rightValue

      utx.inputs.length is nbOfInputs + (nbOfInputs * nbOfTokens)

      utx.fixedOutputs.length is tokens.length + nbOfOutputs + (nbOfTokenHolders * tokens.length) + nbOfInputs

      utx.fixedOutputs.take(nbOfTokens).map(_.tokens) is
        tokens.map { case (tokenId, _) =>
          AVector((tokenId, U256.unsafe(tokenAmount - nbOfTokenHolders)))
        }

      utx.fixedOutputs
        .drop(nbOfTokens)
        .take(nbOfOutputs)
        .foreach(_.tokens is AVector.empty[(TokenId, U256)])

      val gasEstimation =
        estimateWithDifferentP2PKHInputs(utx.inputs.length, utx.fixedOutputs.length)

      utx.gasAmount <= gasEstimation is true

      val changeOutputs = utx.fixedOutputs.drop(nbOfTokens + nbOfOutputs)

      val tokensChange =
        tokens.map { case (tokenId, _) =>
          AVector((tokenId, U256.One))
        } :+ AVector.empty[(TokenId, U256)]

      (0 to nbOfTokenHolders - 1).foreach { i =>
        changeOutputs
          .drop(i * tokensChange.length)
          .take(tokensChange.length)
          .map(_.tokens) is tokensChange
      }

      changeOutputs
        .drop(nbOfTokenHolders * tokensChange.length)
        .foreach(_.tokens is AVector.empty[(TokenId, U256)])

      validateSubmit(utx, privateKeys)
    }

    test(nbOfInputs = 1, nbOfOutputs = 1, nbOfTokens = 1, nbOfTokenHolders = 0)
    test(nbOfInputs = 1, nbOfOutputs = 1, nbOfTokens = 1, nbOfTokenHolders = 1)
    test(nbOfInputs = 1, nbOfOutputs = 1, nbOfTokens = 2, nbOfTokenHolders = 0)
    test(nbOfInputs = 1, nbOfOutputs = 1, nbOfTokens = 2, nbOfTokenHolders = 1)
    test(nbOfInputs = 2, nbOfOutputs = 1, nbOfTokens = 3, nbOfTokenHolders = 0)
    test(nbOfInputs = 2, nbOfOutputs = 1, nbOfTokens = 3, nbOfTokenHolders = 2)
    test(nbOfInputs = 10, nbOfOutputs = 5, nbOfTokens = 5, nbOfTokenHolders = 3)
  }

  it should "fail to transfer multi inputs" in new MultiInputTransactionFixture {
    val block0 = transfer(blockFlow, genesisPriKey, pub1, amount = ALPH.alph(100))
    addAndCheck(blockFlow, block0)
    val block1 = transfer(blockFlow, genesisPriKey, pub2, amount = ALPH.alph(100))
    addAndCheck(blockFlow, block1)

    val input1 = buildInputData(pub1, amount)

    val outputInfos =
      AVector(TxOutputInfo(LockupScript.p2pkh(pub1), ALPH.alph(2 * amount), AVector.empty, None))

    {
      info("same inputs")

      val inputs = AVector(input1, input1)
      blockFlow
        .transferMultiInputs(
          inputs,
          outputInfos,
          nonCoinbaseMinGasPrice,
          Int.MaxValue,
          None
        )
        .rightValue
        .leftValue is "Inputs not unique"
    }

    {
      info("Inputs not equal outputs")

      val inputs = AVector(input1)
      blockFlow
        .transferMultiInputs(
          inputs,
          outputInfos,
          nonCoinbaseMinGasPrice,
          Int.MaxValue,
          None
        )
        .rightValue
        .leftValue is "Total input amount doesn't match total output amount"
    }
    {
      info("Utxos not in same group as lockup script")
      val utxos = Some(
        AVector(AssetOutputRef.from(new ScriptHint(1), TxOutputRef.unsafeKey(Hash.hash("input1"))))
      )

      val inputs = AVector(input1.copy(utxos = utxos))
      blockFlow
        .transferMultiInputs(
          inputs,
          outputInfos,
          nonCoinbaseMinGasPrice,
          Int.MaxValue,
          None
        )
        .rightValue
        .leftValue is "Selected UTXOs different from lockup script group"
    }

    {
      info("Not all gasAmount are defined")
      val i1     = buildInputData(pub1, amount, gas = Some(GasBox.zero))
      val i2     = buildInputData(pub1, amount, gas = None)
      val inputs = AVector(i1, i2)
      blockFlow
        .transferMultiInputs(
          inputs,
          outputInfos,
          nonCoinbaseMinGasPrice,
          Int.MaxValue,
          None
        )
        .rightValue
        .leftValue is "Missing `gasAmount` in some inputs"
    }
  }

  "TxUtils.updateSelectedGas" should "Update selected gas" in new MultiInputTransactionFixture {
    {
      info("empty list")
      blockFlow.updateSelectedGas(AVector.empty, 0) is AVector
        .empty[(TxUtils.InputData, TxUtils.AssetOutputInfoWithGas)]
    }

    {
      info("one address with one input")
      val gas = minimalGas

      val selectedWithGas = buildInputsWithGas(1, gas)

      val entries = AVector((inputData, selectedWithGas))
      val updated = blockFlow.updateSelectedGas(entries, 2)

      updated.length is entries.length

      // Nothing to update
      updated.head._2.gas is gas
    }

    {
      info("one address with many inputs")
      val gas = minimalGas.mulUnsafe(10)

      val selectedWithGas = buildInputsWithGas(10, gas)

      val entries = AVector((inputData, selectedWithGas))
      val updated = blockFlow.updateSelectedGas(entries, 2)

      updated.length is entries.length

      updated.head._2.gas < gas is true
    }

    {
      info("multiple addresses with one input")
      val gas = minimalGas.mulUnsafe(100)

      val selectedWithGas = buildInputsWithGas(1, gas)

      val entries = AVector(
        (inputData, selectedWithGas),
        (inputData, selectedWithGas),
        (inputData, selectedWithGas)
      )

      val updated = blockFlow.updateSelectedGas(entries, 2)

      updated.length is entries.length
      // TODO do we want to test how much was reduced?
      updated.head._2.gas < gas is true
    }
  }

  trait BuildScriptTxFixture extends UnsignedTransactionFixture {
    val script        = StatefulScript.unsafe(AVector.empty)
    val defaultGasFee = nonCoinbaseMinGasPrice * minimalGas
    val tokenId       = TokenId.generate

    protected def buildScriptTx(
        inputs: AVector[(AssetOutputRef, AssetOutput)],
        approvedAlph: U256,
        approvedTokens: (TokenId, U256)*
    ) = {
      val result = UnsignedTransaction
        .buildScriptTx(
          script,
          fromLockupScript,
          fromUnlockScript,
          inputs,
          approvedAlph,
          AVector.from(approvedTokens),
          minimalGas,
          nonCoinbaseMinGasPrice
        )
      result.foreach { tx =>
        tx.inputs.length is inputs.length
        tx.inputs.head.unlockScript is fromUnlockScript
        tx.inputs.tail.foreach(_.unlockScript is UnlockScript.SameAsPrevious)
      }
      result
    }
  }

  "UnsignedTransaction.buildScriptTx" should "fail because of not enough assets" in new BuildScriptTxFixture {
    {
      info("not enough ALPH")
      val inputs = AVector(input("input1", ALPH.oneAlph, fromLockupScript))
      buildScriptTx(inputs, ALPH.oneAlph.subUnsafe(defaultGasFee)).isRight is true
      buildScriptTx(inputs, ALPH.oneAlph.addOneUnsafe()).leftValue is "Not enough balance"
    }

    {
      info("not enough token")
      val inputs = AVector(
        input("input1", ALPH.oneAlph, fromLockupScript),
        input("input2", dustUtxoAmount, fromLockupScript, (tokenId, ALPH.oneAlph))
      )

      buildScriptTx(inputs, ALPH.cent(10), (tokenId, ALPH.oneAlph)).isRight is true
      buildScriptTx(
        inputs,
        ALPH.cent(10),
        (tokenId, ALPH.oneAlph.addOneUnsafe())
      ).leftValue is s"Not enough balance for token $tokenId"
    }

    {
      info("not enough gas fee")
      val inputs = AVector(
        input("input1", ALPH.oneAlph.addUnsafe(defaultGasFee).subOneUnsafe(), fromLockupScript)
      )

      buildScriptTx(inputs, ALPH.oneAlph.subOneUnsafe()).isRight is true
      buildScriptTx(inputs, ALPH.oneAlph).leftValue is "Not enough balance for gas fee"
    }
  }

  "UnsignedTransaction.buildScriptTx" should "fail because of not enough ALPH for change output" in new BuildScriptTxFixture {
    {
      info("not enough ALPH for ALPH change output")
      val inputs       = AVector(input("input1", ALPH.oneAlph, fromLockupScript))
      val approvedAlph = ALPH.oneAlph.subUnsafe(defaultGasFee)

      buildScriptTx(inputs, approvedAlph).isRight is true
      buildScriptTx(inputs, approvedAlph.subUnsafe(dustUtxoAmount)).isRight is true
      buildScriptTx(
        inputs,
        approvedAlph.subOneUnsafe()
      ).leftValue is "Not enough ALPH for ALPH change output, expected 1000000000000000, got 1"
    }

    {
      info("not enough ALPH for token change output")
      val inputs = AVector(
        input("input1", ALPH.oneAlph, fromLockupScript),
        input("input2", dustUtxoAmount, fromLockupScript, (tokenId, ALPH.oneAlph))
      )
      val availableAlph = ALPH.oneAlph.subUnsafe(defaultGasFee)

      buildScriptTx(inputs, availableAlph, (tokenId, ALPH.oneAlph)).isRight is true
      buildScriptTx(
        inputs,
        availableAlph.subUnsafe(dustUtxoAmount),
        (tokenId, ALPH.oneAlph.subOneUnsafe())
      ).isRight is true
      buildScriptTx(
        inputs,
        availableAlph.subOneUnsafe(),
        (tokenId, ALPH.oneAlph.subOneUnsafe())
      ).leftValue is "Not enough ALPH for ALPH and token change output, expected 2000000000000000, got 1000000000000001"
    }
  }

  "UnsignedTransaction.buildScriptTx" should "fail because of inputs not unique" in new BuildScriptTxFixture {
    val inputs = AVector(
      input("input1", ALPH.alph(1), fromLockupScript),
      input("input1", ALPH.alph(2), fromLockupScript)
    )

    buildScriptTx(inputs, ALPH.oneAlph).leftValue is "Inputs not unique"
  }

  "UnsignedTransaction.buildScriptTx" should "fail because of the number of inputs exceeds MaxTxInputNum" in new BuildScriptTxFixture {
    val inputs0 = AVector
      .from(0 until ALPH.MaxTxInputNum)
      .map(idx => input(s"input${idx}", ALPH.alph(1), fromLockupScript))
    val inputs1 = AVector
      .from(0 to ALPH.MaxTxInputNum)
      .map(idx => input(s"input${idx}", ALPH.alph(1), fromLockupScript))

    buildScriptTx(inputs0, ALPH.oneAlph).isRight is true
    buildScriptTx(
      inputs1,
      ALPH.oneAlph
    ).leftValue is "Too many inputs for the transfer, consider to reduce the amount to send, or use the `sweep-address` endpoint to consolidate the inputs first"
  }

  it should "check gas amount: pre-rhone" in new FlowFixture {
    override val configValues: Map[String, Any] = Map(
      ("alephium.network.rhone-hard-fork-timestamp", TimeStamp.Max.millis)
    )
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Leman

    blockFlow.checkProvidedGasAmount(None) isE ()
    blockFlow.checkProvidedGasAmount(Some(minimalGas)) isE ()
    blockFlow.checkProvidedGasAmount(Some(minimalGas.subUnsafe(GasBox.unsafe(1)))).leftValue is
      "Provided gas GasBox(19999) too small, minimal GasBox(20000)"
    blockFlow.checkProvidedGasAmount(Some(maximalGasPerTxPreRhone)) isE ()
    blockFlow
      .checkProvidedGasAmount(Some(maximalGasPerTxPreRhone.addUnsafe(GasBox.unsafe(1))))
      .leftValue is
      "Provided gas GasBox(625001) too large, maximal GasBox(625000)"

    blockFlow.checkEstimatedGasAmount(maximalGasPerTxPreRhone) isE ()
    blockFlow
      .checkEstimatedGasAmount(maximalGasPerTxPreRhone.addUnsafe(GasBox.unsafe(1)))
      .isLeft is true
  }

  it should "check gas amount: rhone" in new FlowFixture {
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Rhone

    blockFlow.checkProvidedGasAmount(None) isE ()
    blockFlow.checkProvidedGasAmount(Some(minimalGas)) isE ()
    blockFlow.checkProvidedGasAmount(Some(minimalGas.subUnsafe(GasBox.unsafe(1)))).leftValue is
      "Provided gas GasBox(19999) too small, minimal GasBox(20000)"
    blockFlow.checkProvidedGasAmount(Some(maximalGasPerTx)) isE ()
    blockFlow.checkProvidedGasAmount(Some(maximalGasPerTx.addUnsafe(GasBox.unsafe(1)))).leftValue is
      "Provided gas GasBox(5000001) too large, maximal GasBox(5000000)"

    blockFlow.checkEstimatedGasAmount(maximalGasPerTx) isE ()
    blockFlow.checkEstimatedGasAmount(maximalGasPerTx.addUnsafe(GasBox.unsafe(1))).isLeft is true
  }

  trait PoLWCoinbaseTxFixture extends FlowFixture with LockupScriptGenerators {
    lazy val chainIndex                 = chainIndexGenForBroker(brokerConfig).sample.get
    lazy val (privateKey, publicKey, _) = genesisKeys(chainIndex.from.value)

    val polwReward: Emission.PoLW = Emission.PoLW(ALPH.alph(1), ALPH.cent(10))

    def buildPoLWCoinbaseTx(uncleSize: Int, fromPublicKey: PublicKey = publicKey) = {
      val uncles = (0 until uncleSize).map { _ =>
        SelectedGhostUncle(model.BlockHash.random, assetLockupGen(chainIndex.to).sample.get, 1)
      }
      blockFlow
        .polwCoinbase(
          chainIndex,
          fromPublicKey,
          LockupScript.p2pkh(fromPublicKey),
          AVector.from(uncles),
          Emission.PoLW(ALPH.alph(1), ALPH.cent(10)),
          U256.Zero,
          TimeStamp.now(),
          ByteString.empty
        )
        .rightValue
        .rightValue
    }
  }

  it should "use minimal gas fee for PoLW coinbase tx" in new PoLWCoinbaseTxFixture {
    (0 until 2).foreach { uncleSize =>
      val tx = buildPoLWCoinbaseTx(uncleSize)
      tx.gasPrice is coinbaseGasPrice
      tx.gasAmount is minimalGas
    }
  }

  it should "not use minimal gas fee for PoLW coinbase tx" in new PoLWCoinbaseTxFixture {
    val fromPublicKey = chainIndex.from.generateKey._2
    (0 until 4).foreach { _ =>
      val block = transfer(blockFlow, privateKey, fromPublicKey, ALPH.cent(3))
      addAndCheck(blockFlow, block)
    }
    val tx = buildPoLWCoinbaseTx(2, fromPublicKey)
    tx.gasPrice is coinbaseGasPrice
    (tx.gasAmount > minimalGas) is true
  }

  it should "return error if there are tokens in PoLW coinbase input" in new PoLWCoinbaseTxFixture {
    val lockupScript = LockupScript.p2pkh(publicKey)
    val inputs = AVector(
      input("input-0", ALPH.cent(10), lockupScript),
      input("input-1", dustUtxoAmount, lockupScript, (TokenId.random, U256.One))
    )
    blockFlow
      .polwCoinbase(
        lockupScript,
        UnlockScript.polw(publicKey),
        AVector.empty,
        ALPH.cent(10),
        inputs.map(i => AssetOutputInfo(i._1, i._2, FlowUtils.PersistedOutput)),
        minimalGas
      )
      .leftValue is "Tokens are not allowed for PoLW input"
  }

  "PoLW change output amount" should "larger than dust amount" in new PoLWCoinbaseTxFixture {
    val fromPublicKey = chainIndex.from.generateKey._2
    val lockupScript  = LockupScript.p2pkh(fromPublicKey)
    val amount        = polwReward.burntAmount.addUnsafe(coinbaseGasFeeSubsidy)
    addAndCheck(blockFlow, transfer(blockFlow, privateKey, fromPublicKey, amount))
    addAndCheck(blockFlow, transfer(blockFlow, privateKey, fromPublicKey, dustUtxoAmount))
    val utxos = blockFlow.getUsableUtxos(None, lockupScript, Int.MaxValue).rightValue
    utxos.length is 2
    utxos.map(_.output.amount).toSet is Set(amount, dustUtxoAmount)

    val tx = buildPoLWCoinbaseTx(0, fromPublicKey)
    tx.inputs.length is 2
    tx.inputs.toSet is utxos
      .map(output => TxInput(output.ref, UnlockScript.polw(fromPublicKey)))
      .toSet

    tx.fixedOutputs.length is 2
    tx.gasAmount is minimalGas
    tx.fixedOutputs(0).amount is Coinbase
      .calcMainChainReward(polwReward.netRewardUnsafe())
      .addUnsafe(polwReward.burntAmount)
    tx.fixedOutputs(1).amount is dustUtxoAmount.addUnsafe(coinbaseGasFeeSubsidy)
  }

  private def input(
      name: String,
      amount: U256,
      lockupScript: LockupScript.Asset,
      tokens: (TokenId, U256)*
  ): (AssetOutputRef, AssetOutput) = {
    val ref =
      AssetOutputRef.from(new ScriptHint(0), TxOutputRef.unsafeKey(Hash.hash(name)))
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
