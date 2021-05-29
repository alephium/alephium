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

import org.alephium.flow.FlowFixture
import org.alephium.flow.validation.TxValidation
import org.alephium.protocol.{ALF, SignatureSchema}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.StatefulScript
import org.alephium.util.{AlephiumSpec, AVector, Bytes, U256}

class FlowUtilsSpec extends AlephiumSpec {
  it should "generate failed tx" in new FlowFixture with NoIndexModelGeneratorsLike {
    val groupIndex = GroupIndex.unsafe(0)
    forAll(assetsToSpendGen(2, 2, 0, 1, p2pkScriptGen(groupIndex))) { assets =>
      val inputs     = assets.map(_.txInput)
      val script     = StatefulScript.alwaysFail
      val unsignedTx = UnsignedTransaction(txScriptOpt = Some(script), inputs, AVector.empty)
      val tx = TransactionTemplate(
        unsignedTx,
        assets.map(asset => SignatureSchema.sign(unsignedTx.hash.bytes, asset.privateKey)),
        AVector.empty
      )

      val worldState = blockFlow.getBestCachedWorldState(groupIndex).rightValue
      assets.foreach { asset =>
        worldState.addAsset(asset.txInput.outputRef, asset.referredOutput).isRight is true
      }
      val firstInput  = assets.head.referredOutput.asInstanceOf[AssetOutput]
      val firstOutput = firstInput.copy(amount = firstInput.amount.subUnsafe(tx.gasFeeUnsafe))
      FlowUtils.generateFullTx(worldState, tx, script).rightValue is
        Transaction(
          unsignedTx,
          AVector.empty,
          firstOutput +: assets.tail.map(_.referredOutput),
          tx.inputSignatures,
          tx.contractSignatures
        )
    }
  }

  it should "check hash order" in new FlowFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    val newBlocks = for {
      i <- 0 to 1
      j <- 0 to 1
    } yield transferOnlyForIntraGroup(blockFlow, ChainIndex.unsafe(i, j))
    newBlocks.foreach { block =>
      addAndCheck(blockFlow, block, 1)
      blockFlow.getWeight(block) isE consensusConfig.minBlockWeight * 1
    }

    newBlocks.map(_.hash).sorted(blockFlow.blockHashOrdering).map(_.bytes) is
      newBlocks.map(_.hash.bytes).sorted(Bytes.byteStringOrdering)

    blockFlow.blockHashOrdering.lt(blockFlow.genesisBlocks(0)(0).hash, newBlocks(0).hash) is true
    blockFlow.blockHashOrdering.lt(blockFlow.genesisBlocks(0)(1).hash, newBlocks(0).hash) is true
    blockFlow.blockHashOrdering.lt(blockFlow.genesisBlocks(0)(0).hash, newBlocks(1).hash) is true
    blockFlow.blockHashOrdering.lt(blockFlow.genesisBlocks(0)(1).hash, newBlocks(1).hash) is true
    blockFlow.blockHashOrdering.lt(blockFlow.genesisBlocks(1)(0).hash, newBlocks(2).hash) is true
    blockFlow.blockHashOrdering.lt(blockFlow.genesisBlocks(1)(1).hash, newBlocks(2).hash) is true
    blockFlow.blockHashOrdering.lt(blockFlow.genesisBlocks(1)(0).hash, newBlocks(3).hash) is true
    blockFlow.blockHashOrdering.lt(blockFlow.genesisBlocks(1)(1).hash, newBlocks(3).hash) is true
  }

  it should "filter double spending txs" in new NoIndexModelGenerators {
    val tx0 = transactionGen(minInputs = 2, maxInputs = 2).sample.get
    val tx1 = transactionGen(minInputs = 2, maxInputs = 2).sample.get
    val tx2 = transactionGen(minInputs = 2, maxInputs = 2).sample.get
    FlowUtils.filterDoubleSpending(AVector(tx0, tx1, tx2)) is AVector(tx0, tx1, tx2)
    FlowUtils.filterDoubleSpending(AVector(tx0, tx0, tx2)) is AVector(tx0, tx2)
    FlowUtils.filterDoubleSpending(AVector(tx0, tx1, tx0)) is AVector(tx0, tx1)
    FlowUtils.filterDoubleSpending(AVector(tx0, tx2, tx2)) is AVector(tx0, tx2)
  }

  it should "detect tx conflicts using bestDeps" in new FlowFixture {
    override val configValues =
      Map(
        ("alephium.consensus.uncle-dependency-gap-time", "10 seconds"),
        ("alephium.broker.broker-num", 1)
      )

    val fromGroup      = Random.nextInt(groups0)
    val chainIndex0    = ChainIndex.unsafe(fromGroup, Random.nextInt(groups0))
    val anotherToGroup = (chainIndex0.to.value + 1 + Random.nextInt(groups0 - 1)) % groups0
    val chainIndex1    = ChainIndex.unsafe(fromGroup, anotherToGroup)
    val block0         = transfer(blockFlow, chainIndex0)
    val block1         = transfer(blockFlow, chainIndex1)

    addAndCheck(blockFlow, block0)
    val groupIndex = GroupIndex.unsafe(fromGroup)
    val tx1        = block1.nonCoinbase.head.toTemplate
    blockFlow.isTxConflicted(groupIndex, tx1) is true
    blockFlow.getMemPool(groupIndex).addNewTx(chainIndex1, tx1)

    val template = blockFlow.prepareBlockFlow(chainIndex1).rightValue
    template.deps.contains(block0.hash) is false
    blockFlow.prepareBlockFlowUnsafe(chainIndex1).transactions.isEmpty is true
  }

  it should "deal with large amount of UTXOs" in new FlowFixture {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val block      = transfer(blockFlow, chainIndex)
    val tx         = block.nonCoinbase.head
    val output     = tx.unsigned.fixedOutputs.head

    val n = ALF.MaxTxInputNum + 1

    val outputs  = AVector.fill(n)(output.copy(amount = ALF.oneAlf))
    val newTx    = Transaction.from(tx.unsigned.inputs, outputs, tx.inputSignatures)
    val newBlock = block.copy(transactions = AVector(newTx))
    blockFlow.addAndUpdateView(newBlock).isRight is true

    val (balance, lockedBalance, utxos) = blockFlow.getBalance(output.lockupScript).rightValue
    balance is U256.unsafe(outputs.sumBy(_.amount.toBigInt))
    lockedBalance is 0
    utxos is n

    val txValidation = TxValidation.build
    val unsignedTx0 = blockFlow
      .transfer(
        keyManager(output.lockupScript).publicKey,
        output.lockupScript,
        None,
        ALF.alf((n - 2).toLong),
        None,
        defaultGasPrice
      )
      .rightValue
      .rightValue
    val tx0 = Transaction.from(unsignedTx0, keyManager(output.lockupScript))
    txValidation.validateMempoolTx(chainIndex, tx0, blockFlow) isE ()

    blockFlow
      .transfer(
        keyManager(output.lockupScript).publicKey,
        output.lockupScript,
        None,
        ALF.alf((n - 1).toLong),
        None,
        defaultGasPrice
      )
      .rightValue
      .leftValue is s"Too many inputs for the transfer, consider to reduce the amount to send"
  }
}
