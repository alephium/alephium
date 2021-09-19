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

import akka.util.ByteString
import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.flow.{AlephiumFlowSpec, FlowFixture}
import org.alephium.flow.core.BlockFlow
import org.alephium.protocol.{ALF, Hash, Signature, SignatureSchema}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{GasBox, GasPrice, StatefulScript}
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

  def failValidation[R](result: BlockValidationResult[R], error: InvalidBlockStatus): Assertion = {
    result.left.value isE error
  }

  class Fixture extends BlockValidation.Impl() {
    def checkCoinbase(block: Block, flow: BlockFlow): BlockValidationResult[Unit] = {
      val groupView = flow.getMutableGroupView(block).rightValue
      checkCoinbase(block.chainIndex, block, groupView)
    }

    implicit class RichTx(tx: Transaction) {
      def update(f: UnsignedTransaction => UnsignedTransaction): Transaction = {
        tx.copy(unsigned = f(tx.unsigned))
      }
    }
  }

  it should "validate group for block" in new Fixture {
    forAll(blockGenOf(brokerConfig)) { block => passCheck(checkGroup(block)) }
    forAll(blockGenNotOf(brokerConfig)) { block => failCheck(checkGroup(block), InvalidGroup) }
  }

  it should "validate nonEmpty transaction list" in new Fixture {
    forAll(blockGen) { block =>
      if (block.transactions.nonEmpty) {
        passCheck(checkNonEmptyTransactions(block))
      }

      val withoutTx = block.copy(transactions = AVector.empty)
      failCheck(checkNonEmptyTransactions(withoutTx), EmptyTransactionList)
    }
  }

  trait CoinbaseFixture extends Fixture {
    val chainIndex = chainIndexGenForBroker(brokerConfig).sample.get

    val block = emptyBlock(blockFlow, chainIndex)
    addAndCheck(blockFlow, block)
    val coinbase = block.coinbase

    implicit class RichBlock(block: Block) {
      def updateUnsignedTx(f: UnsignedTransaction => UnsignedTransaction): Block = {
        val updated = block.coinbase.copy(unsigned = f(coinbase.unsigned))
        block.copy(transactions = AVector(updated))
      }

      def updateTx(f: Transaction => Transaction): Block = {
        block.copy(transactions = AVector(f(block.coinbase)))
      }

      def updateOutput(f: AssetOutput => AssetOutput): Block = {
        val outputs = coinbase.unsigned.fixedOutputs
        updateUnsignedTx(_.copy(fixedOutputs = outputs.replace(0, f(outputs.head))))
      }

      def pass()(implicit validator: (Block) => BlockValidationResult[Unit]) = {
        passCheck(validator(block))
      }

      def fail(error: InvalidBlockStatus)(implicit
          validator: (Block) => BlockValidationResult[Unit]
      ): Assertion = {
        failCheck(validator(block), error)
      }

      def fail()(implicit
         validator: (Block) => BlockValidationResult[Unit],
         error: InvalidBlockStatus
      ): Assertion = {
        fail(error)
      }
    }
  }

  it should "validate coinbase transaction simple format" in new CoinbaseFixture {
    val (privateKey, _) = SignatureSchema.generatePriPub()
    val output0         = assetOutputGen.sample.get
    val emptyOutputs    = AVector.empty[AssetOutput]
    val emptySignatures = AVector.empty[Signature]
    val script          = StatefulScript.alwaysFail
    val testSignatures =
      AVector[Signature](SignatureSchema.sign(coinbase.unsigned.hash.bytes, privateKey))

    implicit val validator                 = (blk: Block) => checkCoinbaseEasy(blk, 1)
    implicit val error: InvalidBlockStatus = InvalidCoinbaseFormat

    info("script")
    block.updateUnsignedTx(_.copy(scriptOpt = None)).pass()
    block.updateUnsignedTx(_.copy(scriptOpt = Some(script))).fail()

    info("gasAmount")
    block.updateUnsignedTx(_.copy(gasAmount = minimalGas)).pass()
    block.updateUnsignedTx(_.copy(gasAmount = GasBox.from(0).value)).fail()

    info("gasPrice")
    block.updateUnsignedTx(_.copy(gasPrice = minimalGasPrice)).pass()
    block.updateUnsignedTx(_.copy(gasPrice = GasPrice(U256.Zero))).fail()

    info("output length")
    block.updateUnsignedTx(_.copy(fixedOutputs = AVector(output0))).pass()
    block.updateUnsignedTx(_.copy(fixedOutputs = emptyOutputs)).fail()

    info("output token")
    block.updateUnsignedTx(_.copy(fixedOutputs = AVector(output0))).pass()
    val outputsWithTokens = AVector(output0.copy(tokens = AVector(Hash.zero -> 10)))
    block.updateUnsignedTx(_.copy(fixedOutputs = outputsWithTokens)).fail()

    info("contract input")
    block.updateTx(_.copy(contractInputs = AVector.empty)).pass()
    val invalidContractInputs = AVector(contractOutputRefGen(GroupIndex.unsafe(0)).sample.get)
    block.updateTx(_.copy(contractInputs = invalidContractInputs)).fail()

    info("generated output")
    block.updateTx(_.copy(generatedOutputs = emptyOutputs.as[TxOutput])).pass()
    block.updateTx(_.copy(generatedOutputs = AVector(output0))).fail()

    info("input signature")
    block.updateTx(_.copy(inputSignatures = emptySignatures)).pass()
    block.updateTx(_.copy(inputSignatures = testSignatures)).fail()

    info("contract signature")
    block.updateTx(_.copy(contractSignatures = emptySignatures)).pass()
    block.updateTx(_.copy(contractSignatures = testSignatures)).fail()
  }

  it should "check coinbase data" in new CoinbaseFixture {
    val coinbaseData = coinbase.unsigned.fixedOutputs.head.additionalData
    val expected     = serialize(CoinbaseFixedData.from(chainIndex, block.header.timestamp))
    coinbaseData.startsWith(expected) is true

    implicit val validator = checkCoinbaseData _

    info("wrong block timestamp")
    val wrongTimestamp = serialize(CoinbaseFixedData.from(chainIndex, TimeStamp.now()))
    block.updateOutput(_.copy(additionalData = wrongTimestamp)).fail(InvalidCoinbaseData)

    info("wrong chain index")
    val wrongChainIndex = {
      val index = chainIndexGen.retryUntil(_ != chainIndex).sample.get
      serialize(CoinbaseFixedData.from(index, block.header.timestamp))
    }
    block.updateOutput(_.copy(additionalData = wrongChainIndex)).fail(InvalidCoinbaseData)

    info("wrong format")
    val wrongFormat = ByteString("wrong-coinbase-data-format")
    block.updateOutput(_.copy(additionalData = wrongFormat)).fail(InvalidCoinbaseData)
  }

  it should "check coinbase locked amount" in new CoinbaseFixture {
    val miningReward       = consensusConfig.emission.reward(block.header).miningReward
    val lockedAmount       = miningReward
    implicit val validator = (blk: Block) => checkLockedReward(blk, lockedAmount)

    info("valid")
    block.pass()

    info("invalid locked amount")
    block.updateOutput(_.copy(amount = U256.One)).fail(InvalidCoinbaseLockedAmount)

    info("invalid lockup period")
    block.updateOutput(_.copy(lockTime = TimeStamp.now())).fail(InvalidCoinbaseLockupPeriod)
  }

  trait RewardFixture extends Fixture {
    val chainIndex = ChainIndex.unsafe(0, 1)
    implicit class RichBlock(block: Block) {
      def replaceCoinbaseReward(reward: U256): Block = {
        val coinbaseOutputNew =
          block.coinbase.unsigned.fixedOutputs.head.copy(amount = reward)
        val coinbaseNew = block.coinbase.copy(
          unsigned = block.coinbase.unsigned.copy(fixedOutputs = AVector(coinbaseOutputNew))
        )
        val txsNew   = block.transactions.replace(block.transactions.length - 1, coinbaseNew)
        val blockNew = block.copy(transactions = txsNew)
        blockNew.coinbaseReward is reward
        blockNew
      }

      def replaceTxGas(reward: U256): Block = {
        val tx       = block.nonCoinbase.head
        val gas      = tx.unsigned.gasAmount
        val gasPrice = reward.divUnsafe(gas.value)
        val newTx    = tx.copy(unsigned = tx.unsigned.copy(gasPrice = GasPrice(gasPrice)))
        val newBlock = block.copy(transactions = AVector(newTx, block.coinbase))
        reMine(blockFlow, chainIndex, newBlock)
      }

      def fail(): Block = {
        failCheck(checkCoinbase(block, blockFlow), InvalidCoinbaseReward)
        block
      }

      def pass(): Block = {
        passCheck(checkCoinbase(block, blockFlow))
        block
      }
    }
  }

  it should "check coinbase reward" in new RewardFixture {
    val block = emptyBlock(blockFlow, ChainIndex.unsafe(0, 1)).pass()

    val miningReward = consensusConfig.emission.reward(block.header).miningReward
    block.replaceCoinbaseReward(miningReward).pass()
    block.replaceCoinbaseReward(miningReward.subUnsafe(minimalGasFee)).fail()
  }

  it should "check gas reward cap" in new RewardFixture {
    val block = transfer(blockFlow, ChainIndex.unsafe(0, 1)).pass()

    val miningReward = consensusConfig.emission.reward(block.header).miningReward
    val block1       = block.replaceTxGas(miningReward).fail()
    block1.replaceCoinbaseReward(miningReward + (block1.gasFee / 2)).pass()

    val block2 = block.replaceTxGas(miningReward * 3).fail()
    block2.replaceCoinbaseReward(miningReward * 2).pass()
  }

  it should "check non-empty txs" in new Fixture {
    val block    = emptyBlock(blockFlow, ChainIndex.unsafe(0, 0))
    val modified = block.copy(transactions = AVector.empty)
    failCheck(checkBlock(modified, blockFlow), EmptyTransactionList)
  }

  it should "check the number of txs" in new Fixture {
    val block = transfer(blockFlow, ChainIndex.unsafe(0, 0))
    val modified0 =
      block.copy(transactions = AVector.fill(maximalTxsInOneBlock)(block.nonCoinbase.head))
    passValidation(checkTxNumber(modified0))

    val modified1 =
      block.copy(transactions = AVector.fill(maximalTxsInOneBlock + 1)(block.nonCoinbase.head))
    failCheck(checkTxNumber(modified1), TooManyTransactions)
    failCheck(checkBlock(modified1, blockFlow), TooManyTransactions)
  }

  it should "check the gas price decreasing" in new Fixture {
    val block = transfer(blockFlow, ChainIndex.unsafe(0, 0))
    val tx    = block.nonCoinbase.head
    tx.unsigned.gasPrice is defaultGasPrice

    val tx1    = tx.update(_.copy(gasPrice = GasPrice(defaultGasPrice.value + 1)))
    val block1 = block.copy(transactions = AVector(tx1, tx))
    passValidation(checkGasPriceDecreasing(block1))

    val block2 = block.copy(transactions = AVector(tx, tx1))
    failValidation(checkBlock(block2, blockFlow), TxGasPriceNonDecreasing)
  }

  it should "check the amount of gas" in new Fixture {
    val block = transfer(blockFlow, ChainIndex.unsafe(0, 0))
    val tx    = block.nonCoinbase.head
    tx.unsigned.gasAmount is minimalGas

    val modified0 = block.copy(transactions = AVector.fill(maximalTxsInOneBlock)(tx))
    passValidation(checkTotalGas(modified0))

    val tx1       = tx.copy(unsigned = tx.unsigned.copy(gasAmount = GasBox.unsafe(minimalGas.value + 1)))
    val modified1 = block.copy(transactions = AVector.fill(maximalTxsInOneBlock - 1)(tx) :+ tx1)
    failValidation(checkTotalGas(modified1), TooManyGasUsed)
    failValidation(checkBlock(modified1, blockFlow), TooManyGasUsed)
  }

  trait DoubleSpendingFixture extends FlowFixture {
    val chainIndex      = ChainIndex.unsafe(0, 0)
    val blockValidation = BlockValidation.build(blockFlow)
  }

  it should "check double spending in a same tx" in new DoubleSpendingFixture {
    val invalidTx = doubleSpendingTx(blockFlow, chainIndex)
    val block     = mine(blockFlow, chainIndex)((_, _) => AVector(invalidTx))
    blockValidation.validate(block, blockFlow) is Left(Right(BlockDoubleSpending))
  }

  it should "check double spending in a same block" in new DoubleSpendingFixture {
    val block0 = transferOnlyForIntraGroup(blockFlow, chainIndex)
    block0.nonCoinbase.length is 1
    blockValidation.validate(block0, blockFlow).isRight is true

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
    val newBlockTs = ALF.LaunchTimestamp.plusSecondsUnsafe(1)
    val block1     = mineWithoutCoinbase(blockFlow, chainIndex, block0.nonCoinbase, newBlockTs)
    blockValidation.validate(block1, blockFlow).isRight is true
  }
}
