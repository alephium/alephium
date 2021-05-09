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

package org.alephium.flow.validation

import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.flow.{AlephiumFlowSpec, FlowFixture}
import org.alephium.io.IOError
import org.alephium.protocol.{ALF, BlockHash, Hash, Signature, SignatureSchema}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.LockupScript
import org.alephium.serde.serialize
import org.alephium.util.{AVector, TimeStamp, U256}

class BlockValidationSpec extends AlephiumFlowSpec with NoIndexModelGeneratorsLike {
  def passCheck[T](result: BlockValidationResult[T]): Assertion = {
    result.isRight is true
  }

  def failCheck[T](result: BlockValidationResult[T], error: InvalidBlockStatus): Assertion = {
    result.left.value isE error
  }

  def passValidation(result: BlockValidationResult[Unit]): Assertion = {
    result.isRight is true
  }

  def failValidation(result: BlockValidationResult[Unit], error: InvalidBlockStatus): Assertion = {
    result.left.value isE error
  }

  class Fixture extends BlockValidation.Impl()

  it should "validate group for block" in new Fixture {
    forAll(blockGenOf(brokerConfig)) { block => passCheck(checkGroup(block)) }
    forAll(blockGenNotOf(brokerConfig)) { block => failCheck(checkGroup(block), InvalidGroup) }
  }

  it should "validate nonEmpty transaction list" in new Fixture {
    val block0 = blockGen.retryUntil(_.transactions.nonEmpty).sample.get
    val block1 = block0.copy(transactions = AVector.empty)
    passCheck(checkNonEmptyTransactions(block0))
    failCheck(checkNonEmptyTransactions(block1), EmptyTransactionList)
  }

  def coinbase(chainIndex: ChainIndex, gasFee: U256, lockupScript: LockupScript): Transaction = {
    Transaction.coinbase(chainIndex, gasFee, lockupScript, Target.Max, TimeStamp.zero)
  }

  it should "validate coinbase transaction simple format" in new Fixture {
    val (privateKey, publicKey) = SignatureSchema.generatePriPub()
    val lockupScript            = LockupScript.p2pkh(publicKey)
    val block0 =
      Block.from(
        AVector.fill(groupConfig.depsNum)(BlockHash.zero),
        Hash.zero,
        AVector.empty,
        consensusConfig.maxMiningTarget,
        TimeStamp.zero,
        0
      )

    val input0          = txInputGen.sample.get
    val output0         = assetOutputGen.sample.get
    val emptyInputs     = AVector.empty[TxInput]
    val emptyOutputs    = AVector.empty[AssetOutput]
    val emptySignatures = AVector.empty[Signature]

    val coinbase1     = coinbase(block0.chainIndex, 0, lockupScript)
    val testSignature = AVector(SignatureSchema.sign(coinbase1.unsigned.hash.bytes, privateKey))
    val block1        = block0.copy(transactions = AVector(coinbase1))
    passCheck(checkCoinbaseEasy(block1, 0, 1))

    val coinbase2 = Transaction.from(AVector(input0), AVector(output0), emptySignatures)
    val block2    = block0.copy(transactions = AVector(coinbase2))
    failCheck(checkCoinbaseEasy(block2, 0, 1), InvalidCoinbaseFormat)

    val coinbase3 = Transaction.from(emptyInputs, emptyOutputs, testSignature)
    val block3    = block0.copy(transactions = AVector(coinbase3))
    failCheck(checkCoinbaseEasy(block3, 0, 1), InvalidCoinbaseFormat)

    val coinbase4 = Transaction.from(emptyInputs, AVector(output0), testSignature)
    val block4    = block0.copy(transactions = AVector(coinbase4))
    failCheck(checkCoinbaseEasy(block4, 0, 1), InvalidCoinbaseFormat)

    val coinbase5 = Transaction.from(AVector(input0), AVector(output0), emptySignatures)
    val block5    = block0.copy(transactions = AVector(coinbase5))
    failCheck(checkCoinbaseEasy(block5, 0, 1), InvalidCoinbaseFormat)

    val coinbase6 = Transaction.from(emptyInputs, emptyOutputs, emptySignatures)
    val block6    = block0.copy(transactions = AVector(coinbase6))
    failCheck(checkCoinbaseEasy(block6, 0, 1), InvalidCoinbaseFormat)

    val coinbase7 = Transaction.from(emptyInputs, emptyOutputs, AVector(output0), testSignature)
    val block7    = block0.copy(transactions = AVector(coinbase7))
    failCheck(checkCoinbaseEasy(block7, 0, 1), InvalidCoinbaseFormat)

    val coinbase8 = Transaction.from(emptyInputs, emptyOutputs, AVector(output0), emptySignatures)
    val block8    = block0.copy(transactions = AVector(coinbase8))
    failCheck(checkCoinbaseEasy(block8, 0, 1), InvalidCoinbaseFormat)
  }

  it should "check coinbase data" in new Fixture {
    val block        = blockGenOf(brokerConfig).sample.get
    val chainIndex   = block.chainIndex
    val coinbaseData = block.coinbase.unsigned.fixedOutputs.head.additionalData
    val expected     = serialize(CoinbaseFixedData.from(chainIndex, block.header.timestamp))
    coinbaseData.startsWith(expected) is true
  }

  it should "check coinbase reward" in new Fixture {
    val block = blockGenOf(brokerConfig).filter(_.nonCoinbase.nonEmpty).sample.get
    passCheck(checkCoinbase(block, blockFlow))

    val miningReward      = consensusConfig.emission.miningReward(block.header)
    val coinbaseOutputNew = block.coinbase.unsigned.fixedOutputs.head.copy(amount = miningReward)
    val coinbaseNew = block.coinbase.copy(
      unsigned = block.coinbase.unsigned.copy(fixedOutputs = AVector(coinbaseOutputNew))
    )
    val txsNew   = block.transactions.replace(block.transactions.length - 1, coinbaseNew)
    val blockNew = block.copy(transactions = txsNew)
    failCheck(checkCoinbase(blockNew, blockFlow), InvalidCoinbaseReward)
  }

  trait DoubleSpendingFixture extends FlowFixture {
    val chainIndex      = ChainIndex.unsafe(0, 0)
    val blockValidation = BlockValidation.build(blockFlow.brokerConfig, blockFlow.consensusConfig)
  }

  it should "check double spending in a same tx" in new DoubleSpendingFixture {
    val invalidTx = doubleSpendingTx(blockFlow, chainIndex)
    val block     = mine(blockFlow, chainIndex)((_, _) => AVector(invalidTx))
    blockValidation.validate(block, blockFlow) is Left(Right(BlockDoubleSpending))
  }

  it should "check double spending in a same block" in new DoubleSpendingFixture {
    val block0 = transferOnlyForIntraGroup(blockFlow, chainIndex)
    block0.nonCoinbase.length is 1
    blockValidation.validate(block0, blockFlow) isE ()

    val block1 = mine(blockFlow, chainIndex)((_, _) => block0.nonCoinbase ++ block0.nonCoinbase)
    block1.nonCoinbase.length is 2
    blockValidation.validate(block1, blockFlow) is Left(Right(BlockDoubleSpending))
  }

  it should "check double spending in block dependencies (1)" in new DoubleSpendingFixture {
    val block0 = transfer(blockFlow, ChainIndex.unsafe(0, 0))
    val block1 = transfer(blockFlow, ChainIndex.unsafe(0, 1))

    addAndCheck(blockFlow, block0, 1)
    addAndCheck(blockFlow, block1, 1)
    blockFlow.isConflicted(AVector(block1.hash, block0.hash), blockFlow.getBlockUnsafe) is true

    val block2 = transfer(blockFlow, ChainIndex.unsafe(0, 0))
    val newDeps2 = block2.header.blockDeps.deps
      .replace(brokerConfig.groups - 1, block0.hash)
      .replace(brokerConfig.groups, block1.hash)
    val block3 = mine(
      blockFlow,
      chainIndex,
      newDeps2,
      block2.transactions,
      block2.header.timestamp
    )

    blockFlow.isConflicted(block3.header.outDeps, blockFlow.getBlockUnsafe) is true
    blockValidation.validate(block3, blockFlow) is Left(Right(InvalidFlowTxs))

    val block4 = transfer(blockFlow, ChainIndex.unsafe(0, 1))
    val newDeps4 = block4.header.blockDeps.deps
      .replace(brokerConfig.groups - 1, block0.hash)
      .replace(brokerConfig.groups, block1.hash)
    val block5 =
      mine(
        blockFlow,
        ChainIndex.unsafe(0, 1),
        newDeps4,
        block4.transactions,
        block4.header.timestamp
      )

    blockFlow.isConflicted(block5.header.outDeps, blockFlow.getBlockUnsafe) is true
    blockValidation.validate(block5, blockFlow) is Left(Right(InvalidFlowTxs))
  }

  it should "check double spending in block dependencies (2)" in new DoubleSpendingFixture {
    val block0 = transfer(blockFlow, ChainIndex.unsafe(0, 1))
    addAndCheck(blockFlow, block0)

    val block1 = emptyBlock(blockFlow, ChainIndex.unsafe(0, 0))
    addAndCheck(blockFlow, block1)

    val block2 = mine(blockFlow, ChainIndex.unsafe(0, 0))((_, _) => block0.nonCoinbase)
    block2.nonCoinbaseLength is 1
    blockValidation.validate(block2, blockFlow) is Left(Right(InvalidFlowTxs))

    val block3 = mine(blockFlow, ChainIndex.unsafe(0, 1))((_, _) => block0.nonCoinbase)
    block3.nonCoinbaseLength is 1
    blockValidation.validate(block3, blockFlow) is Left(Right(InvalidFlowTxs))
  }

  it should "check double spending in block dependencies (3)" in new DoubleSpendingFixture {
    val block0 = transfer(blockFlow, ChainIndex.unsafe(0, 0))
    addAndCheck(blockFlow, block0)

    val block1 = mine(blockFlow, ChainIndex.unsafe(0, 0))((_, _) => block0.nonCoinbase)
    block1.nonCoinbaseLength is 1
    blockValidation.validate(block1, blockFlow) is Left(Right(InvalidFlowTxs))

    val block2 = mine(blockFlow, ChainIndex.unsafe(0, 1))((_, _) => block0.nonCoinbase)
    block2.nonCoinbaseLength is 1
    blockValidation.validate(block2, blockFlow) is Left(Right(InvalidFlowTxs))
  }

  it should "validate old blocks" in new DoubleSpendingFixture {
    val block0     = transfer(blockFlow, ChainIndex.unsafe(0, 0))
    val newBlockTs = ALF.GenesisTimestamp.plusSecondsUnsafe(1)
    val block1     = mineWithoutCoinbase(blockFlow, chainIndex, block0.nonCoinbase, newBlockTs)

    val newOutTips = block1.header.outDeps
    val intraDep   = block1.header.intraDep
    val oldOutTips =
      blockFlow.getOutTips(blockFlow.getBlockHeaderUnsafe(intraDep), inclusive = false)
    val diff = blockFlow.getTipsDiffUnsafe(newOutTips, oldOutTips)
    assertThrows[IOError.KeyNotFound[_]](
      !blockFlow.isConflicted(block1.hash +: diff, blockFlow.getBlockUnsafe)
    )

    blockValidation.validate(block1, blockFlow) isE ()
  }
}
