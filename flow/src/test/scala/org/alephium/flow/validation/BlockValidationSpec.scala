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

  trait CoinbaseFixture extends BlockValidation.Impl() {
    val chainIndex = chainIndexGenForBroker(brokerConfig).sample.get

    val block = emptyBlock(blockFlow, chainIndex)
    addAndCheck(blockFlow, block)

    def checkCoinbase(block: Block): BlockValidationResult[Unit] = {
      val groupView = blockFlow.getMutableGroupView(block).rightValue
      checkCoinbase(block.chainIndex, block, groupView)
    }

    implicit class RichBlock(block: Block) {
      def updateUnsignedTx(f: UnsignedTransaction => UnsignedTransaction): Block = {
        val updated = block.coinbase.copy(unsigned = f(block.coinbase.unsigned))
        block.copy(transactions = block.nonCoinbase :+ updated)
      }

      def updateTx(f: Transaction => Transaction): Block = {
        block.copy(transactions = block.nonCoinbase :+ f(block.coinbase))
      }

      def updateOutput(f: AssetOutput => AssetOutput): Block = {
        val outputs = block.coinbase.unsigned.fixedOutputs
        updateUnsignedTx(_.copy(fixedOutputs = outputs.replace(0, f(outputs.head))))
      }

      def pass()(implicit validator: (Block) => BlockValidationResult[Unit]) = {
        passCheck(validator(block))
      }

      def replaceTxGas(gas: U256): Block = {
        assert(block.transactions.length >= 2) // at least 1 non-coinbase tx
        val tx        = block.nonCoinbase.head
        val gasAmount = tx.unsigned.gasAmount
        val gasPrice  = gas.divUnsafe(gasAmount.value)
        val newTx     = tx.copy(unsigned = tx.unsigned.copy(gasPrice = GasPrice(gasPrice)))
        block.copy(transactions = newTx +: block.transactions.tail)
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

  it should "validate group for block" in new BlockValidation.Impl() {
    forAll(blockGenOf(brokerConfig)) { block => passCheck(checkGroup(block)) }
    forAll(blockGenNotOf(brokerConfig)) { block => failCheck(checkGroup(block), InvalidGroup) }
  }

  it should "validate nonEmpty transaction list" in new BlockValidation.Impl() {
    forAll(blockGen) { block =>
      if (block.transactions.nonEmpty) {
        passCheck(checkNonEmptyTransactions(block))
      }

      val withoutTx = block.copy(transactions = AVector.empty)
      failCheck(checkNonEmptyTransactions(withoutTx), EmptyTransactionList)
    }
  }

  it should "validate coinbase transaction simple format" in new CoinbaseFixture {
    val (privateKey, _) = SignatureSchema.generatePriPub()
    val output0         = assetOutputGen.sample.get
    val emptyOutputs    = AVector.empty[AssetOutput]
    val emptySignatures = AVector.empty[Signature]
    val script          = StatefulScript.alwaysFail
    val testSignatures =
      AVector[Signature](SignatureSchema.sign(block.coinbase.unsigned.hash.bytes, privateKey))

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
    val coinbaseData = block.coinbase.unsigned.fixedOutputs.head.additionalData
    val expected     = serialize(CoinbaseFixedData.from(chainIndex, block.header.timestamp))
    coinbaseData.startsWith(expected) is true

    implicit val validator = (blk: Block) => checkCoinbaseData(blk.chainIndex, blk)

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

  it should "check coinbase reward" in new CoinbaseFixture {
    implicit val validator = checkCoinbase _

    val miningReward = consensusConfig.emission.reward(block.header).miningReward
    block.updateOutput(_.copy(amount = miningReward)).pass()

    val invalidMiningReward = miningReward.subUnsafe(1)
    block.updateOutput(_.copy(amount = invalidMiningReward)).fail(InvalidCoinbaseReward)
  }

  it should "check gas reward cap" in new CoinbaseFixture {
    implicit val validator = checkCoinbase _

    val block0 = transfer(blockFlow, chainIndex)
    block0.pass()

    info("gas reward is set to 1/2 of miningReward, miningReward not enough")
    val miningReward = consensusConfig.emission.reward(block0.header).miningReward
    val block1       = block0.replaceTxGas(miningReward)
    block1.fail(InvalidCoinbaseReward)

    info("adjust the miningReward to account for the adjusted gas reward")
    block1.updateOutput(_.copy(amount = miningReward + (block1.gasFee / 2))).pass()

    info("gas reward is set to the max: the same as miningReward, miningReward not enough")
    val block2 = block0.replaceTxGas(miningReward * 3)
    block2.fail(InvalidCoinbaseReward)

    info("adjust the miningReward to account for the adjusted gas reward")
    block2.updateOutput(_.copy(amount = miningReward * 2)).pass()
  }

  it should "check non-empty txs" in new BlockValidation.Impl() {
    val block    = emptyBlock(blockFlow, ChainIndex.unsafe(0, 0))
    val modified = block.copy(transactions = AVector.empty)
    failCheck(checkBlock(modified, blockFlow), EmptyTransactionList)
  }

  it should "check the number of txs" in new BlockValidation.Impl() {
    val block = transfer(blockFlow, ChainIndex.unsafe(0, 0))
    val modified0 =
      block.copy(transactions = AVector.fill(maximalTxsInOneBlock)(block.nonCoinbase.head))
    passCheck(checkTxNumber(modified0))

    val modified1 =
      block.copy(transactions = AVector.fill(maximalTxsInOneBlock + 1)(block.nonCoinbase.head))
    failCheck(checkTxNumber(modified1), TooManyTransactions)
    failCheck(checkBlock(modified1, blockFlow), TooManyTransactions)
  }

  it should "check the gas price decreasing" in new BlockValidation.Impl() {
    val block = transfer(blockFlow, ChainIndex.unsafe(0, 0))
    val tx    = block.nonCoinbase.head
    tx.unsigned.gasPrice is defaultGasPrice

    val tx1 = tx.copy(
      unsigned = tx.unsigned.copy(gasPrice = GasPrice(defaultGasPrice.value + 1))
    )
    val block1 = block.copy(transactions = AVector(tx1, tx))
    passCheck(checkGasPriceDecreasing(block1))

    val block2 = block.copy(transactions = AVector(tx, tx1))
    failCheck(checkBlock(block2, blockFlow), TxGasPriceNonDecreasing)
  }

  it should "check the amount of gas" in new BlockValidation.Impl() {
    val block = transfer(blockFlow, ChainIndex.unsafe(0, 0))
    val tx    = block.nonCoinbase.head
    tx.unsigned.gasAmount is minimalGas

    val modified0 = block.copy(transactions = AVector.fill(maximalTxsInOneBlock)(tx))
    passCheck(checkTotalGas(modified0))

    val tx1       = tx.copy(unsigned = tx.unsigned.copy(gasAmount = GasBox.unsafe(minimalGas.value + 1)))
    val modified1 = block.copy(transactions = AVector.fill(maximalTxsInOneBlock - 1)(tx) :+ tx1)
    failCheck(checkTotalGas(modified1), TooManyGasUsed)
    failCheck(checkBlock(modified1, blockFlow), TooManyGasUsed)
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
