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

import org.alephium.flow.FlowFixture
import org.alephium.flow.mempool.MemPool
import org.alephium.flow.validation.TxValidation
import org.alephium.protocol.{ALPH, Generators, Hash}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{GasBox, LockupScript, UnlockScript}
import org.alephium.util.{AlephiumSpec, AVector, TimeStamp, U256}

class TxUtilsSpec extends AlephiumSpec {
  it should "consider use minimal gas fee" in new FlowFixture {
    val chainIndex            = ChainIndex.unsafe(0, 0)
    val (genesisPriKey, _, _) = genesisKeys(0)
    val (toPriKey, _)         = chainIndex.from.generateKey
    val block0                = transfer(blockFlow, genesisPriKey, toPriKey.publicKey, amount = minimalGasFee)
    addAndCheck(blockFlow, block0)
    val block1 = transfer(blockFlow, genesisPriKey, toPriKey.publicKey, amount = minimalGasFee)
    addAndCheck(blockFlow, block1)

    blockFlow
      .transfer(
        toPriKey.publicKey,
        getGenesisLockupScript(chainIndex),
        None,
        minimalGasFee / 2,
        None,
        minimalGasPrice
      )
      .rightValue
      .isRight is true
  }

  it should "use default gas price" in new FlowFixture {
    val chainIndex            = ChainIndex.unsafe(0, 1)
    val (genesisPriKey, _, _) = genesisKeys(0)
    val (toPriKey, _)         = chainIndex.from.generateKey
    val block                 = transfer(blockFlow, genesisPriKey, toPriKey.publicKey, amount = minimalGasFee)
    val tx                    = block.nonCoinbase.head
    tx.gasFeeUnsafe is defaultGasFee
    defaultGasFee is ALPH.nanoAlph(20000 * 100)
  }

  it should "consider outputs for inter-group blocks" in new FlowFixture {
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
        defaultGasPrice
      )
      .rightValue
      .rightValue
    val tx = TransactionTemplate.from(unsignedTx, genesisPriKey)
    TxValidation.build.validateGrandPoolTxTemplate(tx, blockFlow) isE ()
    blockFlow
      .getMemPool(chainIndex)
      .addNewTx(chainIndex, tx, TimeStamp.now()) is MemPool.AddedToSharedPool
    TxValidation.build.validateMempoolTxTemplate(tx, blockFlow) isE ()
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
      .leftValue
      .startsWith("New tokens found in outputs") is true
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
      .leftValue
      .startsWith("Not enough balance for token") is true
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
        info(s"minimalAlphAmountPerTxOutput is ${minimalAlphAmountPerTxOutput(1)}")
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
        .leftValue
        .startsWith("Not enough Alph for transaction output") is true
    }

    {
      info("without tokens")
      val inputs = {
        val input1 = input("input1", ALPH.oneAlph, fromLockupScript)
        val input2 = input("input2", ALPH.alph(3), fromLockupScript)
        AVector(input1, input2)
      }

      val outputs = {
        info(s"minimalAlphAmountPerTxOutput is ${minimalAlphAmountPerTxOutput(0)}")
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
        .leftValue
        .startsWith("Not enough Alph for transaction output") is true
    }
  }

  it should "fail when change output doesn't have minimal amount of Alph" in new UnsignedTransactionFixture {
    {
      info("with tokens")
      val tokenId1 = Hash.hash("tokenId1")
      val tokenId2 = Hash.hash("tokenId2")

      val inputs = {
        val input1Amount = defaultGasFee.addUnsafe(minimalAlphAmountPerTxOutput(1)).subUnsafe(1)
        val input1       = input("input1", input1Amount, fromLockupScript, (tokenId2, U256.unsafe(10)))
        val input2       = input("input2", ALPH.alph(3), fromLockupScript, (tokenId1, U256.unsafe(50)))
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
        .leftValue
        .startsWith("Not enough Alph for change output") is true
    }

    {
      info("without tokens")
      val inputs = {
        val input1Amount = defaultGasFee.addUnsafe(minimalAlphAmountPerTxOutput(0)).subUnsafe(1)
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
        .leftValue
        .startsWith("Not enough Alph for change output") is true
    }
  }

  it should "estimate gas for sweep all tx" in new FlowFixture {
    val txValidation = TxValidation.build
    def test(inputNum: Int) = {
      val blockflow = isolatedBlockFlow()
      val block     = transfer(blockflow, ChainIndex.unsafe(0, 0))
      val tx        = block.nonCoinbase.head
      val output    = tx.unsigned.fixedOutputs.head
      val outputs   = AVector.fill(inputNum)(output.copy(amount = ALF.oneAlf))
      val newTx     = Transaction.from(tx.unsigned.inputs, outputs, tx.inputSignatures)
      val newBlock  = block.copy(transactions = AVector(newTx))
      addAndUpdateView(blockflow, newBlock)

      val unsignedTx = blockflow
        .sweepAll(
          keyManager(output.lockupScript).publicKey,
          output.lockupScript,
          None,
          None,
          defaultGasPrice
        )
        .rightValue
        .rightValue
      unsignedTx.gasAmount is UtxoUtils.estimateSweepAllTxGas(inputNum)
      val sweepTx = Transaction.from(unsignedTx, keyManager(output.lockupScript))
      txValidation.validateTxOnlyForTest(sweepTx, blockflow) isE ()
    }

    (1 to 10).foreach(test)
  }

  trait LargeUtxos extends FlowFixture {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val block      = transfer(blockFlow, chainIndex)
    val tx         = block.nonCoinbase.head
    val output     = tx.unsigned.fixedOutputs.head

    val n = ALPH.MaxTxInputNum + 1

    val outputs  = AVector.fill(n)(output.copy(amount = ALPH.oneAlph))
    val newTx    = Transaction.from(tx.unsigned.inputs, outputs, tx.inputSignatures)
    val newBlock = block.copy(transactions = AVector(newTx))
    addAndUpdateView(blockFlow, newBlock)

    val (balance, lockedBalance, utxos) =
      blockFlow.getBalance(output.lockupScript, Int.MaxValue).rightValue
    balance is U256.unsafe(outputs.sumBy(_.amount.toBigInt))
    lockedBalance is 0
    utxos is n
  }

  it should "transfer with large amount of UTXOs" in new LargeUtxos {
    val txValidation = TxValidation.build
    val unsignedTx0 = blockFlow
      .transfer(
        keyManager(output.lockupScript).publicKey,
        output.lockupScript,
        None,
        ALPH.alph((n - 2).toLong),
        Some(GasBox.unsafe(600000)),
        defaultGasPrice
      )
      .rightValue
      .rightValue
    val tx0 = Transaction.from(unsignedTx0, keyManager(output.lockupScript))
    tx0.unsigned.inputs.length is n - 1
    tx0.inputSignatures.length is 1
    txValidation.validateTxOnlyForTest(tx0, blockFlow) isE ()

    blockFlow
      .transfer(
        keyManager(output.lockupScript).publicKey,
        output.lockupScript,
        None,
        ALPH.alph((n - 1).toLong),
        None,
        defaultGasPrice
      )
      .rightValue
      .leftValue is s"Too many inputs for the transfer, consider to reduce the amount to send"
  }

  it should "sweep as much as we can" in new LargeUtxos {
    val txValidation = TxValidation.build
    val unsignedTx = blockFlow
      .sweepAll(
        keyManager(output.lockupScript).publicKey,
        output.lockupScript,
        None,
        None,
        defaultGasPrice
      )
      .rightValue
      .rightValue
    val sweepTx = Transaction.from(unsignedTx, keyManager(output.lockupScript))
    txValidation.validateTxOnlyForTest(sweepTx, blockFlow) isE ()
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
      alphAmount: U256,
      tokens: (TokenId, U256)*
  ): UnsignedTransaction.TxOutputInfo = {
    UnsignedTransaction.TxOutputInfo(
      lockupScript,
      alphAmount,
      AVector.from(tokens),
      lockTime = None
    )
  }
}
