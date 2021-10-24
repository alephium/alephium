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

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.flow.core.BlockFlow
import org.alephium.protocol.{ALF, Hash, Signature, SignatureSchema}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{GasBox, GasPrice, StatefulScript}
import org.alephium.serde.serialize
import org.alephium.util.{AVector, TimeStamp, U256}

class BlockValidationSpec extends AlephiumFlowSpec with NoIndexModelGeneratorsLike {

  trait Fixture extends BlockValidation.Impl() {
    val blockFlow  = isolatedBlockFlow()
    val chainIndex = chainIndexGenForBroker(brokerConfig).sample.value

    implicit class RichBlock(block: Block) {
      object Coinbase {
        def unsignedTx(f: UnsignedTransaction => UnsignedTransaction): Block = {
          val updated = block.coinbase.copy(unsigned = f(block.coinbase.unsigned))
          block.copy(transactions = block.nonCoinbase :+ updated)
        }

        def tx(f: Transaction => Transaction): Block = {
          block.copy(transactions = block.nonCoinbase :+ f(block.coinbase))
        }

        def output(f: AssetOutput => AssetOutput): Block = {
          val outputs = block.coinbase.unsigned.fixedOutputs
          unsignedTx(_.copy(fixedOutputs = outputs.replace(0, f(outputs.head))))
        }
      }

      def pass()(implicit validator: (Block) => BlockValidationResult[Unit]) = {
        validator(block).isRight is true
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
        validator(block).left.value isE error
      }

      def fail()(implicit
          validator: (Block) => BlockValidationResult[Unit],
          error: InvalidBlockStatus
      ): Assertion = {
        fail(error)
      }
    }

    def checkBlockUnit(block: Block, flow: BlockFlow): BlockValidationResult[Unit] = {
      checkBlock(block, flow).map(_ => ())
    }
  }

  it should "validate group for block" in new Fixture {
    implicit val validator = checkGroup _

    forAll(blockGenOf(brokerConfig))(_.pass())
    forAll(blockGenNotOf(brokerConfig))(_.fail(InvalidGroup))
  }

  it should "validate nonEmpty transaction list" in new Fixture {
    implicit val validator = checkNonEmptyTransactions _

    forAll(blockGen) { block =>
      if (block.transactions.nonEmpty) {
        block.pass()
      }

      val withoutTx = block.copy(transactions = AVector.empty)
      withoutTx.fail(EmptyTransactionList)
    }
  }

  it should "validate coinbase transaction simple format" in new Fixture {
    val block           = emptyBlock(blockFlow, chainIndex)
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
    block.Coinbase.unsignedTx(_.copy(scriptOpt = None)).pass()
    block.Coinbase.unsignedTx(_.copy(scriptOpt = Some(script))).fail()

    info("gasAmount")
    block.Coinbase.unsignedTx(_.copy(gasAmount = minimalGas)).pass()
    block.Coinbase.unsignedTx(_.copy(gasAmount = GasBox.from(0).value)).fail()

    info("gasPrice")
    block.Coinbase.unsignedTx(_.copy(gasPrice = minimalGasPrice)).pass()
    block.Coinbase.unsignedTx(_.copy(gasPrice = GasPrice(U256.Zero))).fail()

    info("output length")
    block.Coinbase.unsignedTx(_.copy(fixedOutputs = AVector(output0))).pass()
    block.Coinbase.unsignedTx(_.copy(fixedOutputs = emptyOutputs)).fail()

    info("output token")
    block.Coinbase.unsignedTx(_.copy(fixedOutputs = AVector(output0))).pass()
    val outputsWithTokens = AVector(output0.copy(tokens = AVector(Hash.zero -> 10)))
    block.Coinbase.unsignedTx(_.copy(fixedOutputs = outputsWithTokens)).fail()

    info("contract input")
    block.Coinbase.tx(_.copy(contractInputs = AVector.empty)).pass()
    val invalidContractInputs = AVector(contractOutputRefGen(GroupIndex.unsafe(0)).sample.get)
    block.Coinbase.tx(_.copy(contractInputs = invalidContractInputs)).fail()

    info("generated output")
    block.Coinbase.tx(_.copy(generatedOutputs = emptyOutputs.as[TxOutput])).pass()
    block.Coinbase.tx(_.copy(generatedOutputs = AVector(output0))).fail()

    info("input signature")
    block.Coinbase.tx(_.copy(inputSignatures = emptySignatures)).pass()
    block.Coinbase.tx(_.copy(inputSignatures = testSignatures)).fail()

    info("contract signature")
    block.Coinbase.tx(_.copy(scriptSignatures = emptySignatures)).pass()
    block.Coinbase.tx(_.copy(scriptSignatures = testSignatures)).fail()
  }

  it should "check coinbase data" in new Fixture {
    val block        = emptyBlock(blockFlow, chainIndex)
    val coinbaseData = block.coinbase.unsigned.fixedOutputs.head.additionalData
    val expected     = serialize(CoinbaseFixedData.from(chainIndex, block.header.timestamp))
    coinbaseData.startsWith(expected) is true

    implicit val validator = (blk: Block) => checkCoinbaseData(blk.chainIndex, blk)

    info("wrong block timestamp")
    val wrongTimestamp =
      serialize(CoinbaseFixedData.from(chainIndex, TimeStamp.now().plusSecondsUnsafe(5)))
    block.Coinbase.output(_.copy(additionalData = wrongTimestamp)).fail(InvalidCoinbaseData)

    info("wrong chain index")
    val wrongChainIndex = {
      val index = chainIndexGen.retryUntil(_ != chainIndex).sample.get
      serialize(CoinbaseFixedData.from(index, block.header.timestamp))
    }
    block.Coinbase.output(_.copy(additionalData = wrongChainIndex)).fail(InvalidCoinbaseData)

    info("wrong format")
    val wrongFormat = ByteString("wrong-coinbase-data-format")
    block.Coinbase.output(_.copy(additionalData = wrongFormat)).fail(InvalidCoinbaseData)
  }

  it should "check coinbase locked amount" in new Fixture {
    val block              = emptyBlock(blockFlow, chainIndex)
    val miningReward       = consensusConfig.emission.reward(block.header).miningReward
    val lockedAmount       = miningReward
    implicit val validator = (blk: Block) => checkLockedReward(blk, lockedAmount)

    info("valid")
    block.pass()

    info("invalid locked amount")
    block.Coinbase.output(_.copy(amount = U256.One)).fail(InvalidCoinbaseLockedAmount)

    info("invalid lockup period")
    block.Coinbase.output(_.copy(lockTime = TimeStamp.now())).fail(InvalidCoinbaseLockupPeriod)
  }

  it should "check coinbase reward" in new Fixture {
    val block = emptyBlock(blockFlow, chainIndex)
    implicit val validator = (blk: Block) => {
      val groupView = blockFlow.getMutableGroupView(blk).rightValue
      checkCoinbase(blk.chainIndex, blk, groupView)
    }

    val miningReward = consensusConfig.emission.reward(block.header).miningReward
    block.Coinbase.output(_.copy(amount = miningReward)).pass()

    val invalidMiningReward = miningReward.subUnsafe(1)
    block.Coinbase.output(_.copy(amount = invalidMiningReward)).fail(InvalidCoinbaseReward)
  }

  it should "check gas reward cap" in new Fixture {
    implicit val validator = (blk: Block) => {
      val groupView = blockFlow.getMutableGroupView(blk).rightValue
      checkCoinbase(blk.chainIndex, blk, groupView)
    }

    val block0 = transfer(blockFlow, chainIndex)
    block0.pass()

    info("gas reward is set to 1/2 of miningReward, miningReward not enough")
    val miningReward = consensusConfig.emission.reward(block0.header).miningReward
    val block1       = block0.replaceTxGas(miningReward)
    block1.fail(InvalidCoinbaseReward)

    info("adjust the miningReward to account for the adjusted gas reward")
    block1.Coinbase.output(_.copy(amount = miningReward + (block1.gasFee / 2))).pass()

    info("gas reward is set to the max: the same as miningReward, miningReward not enough")
    val block2 = block0.replaceTxGas(miningReward * 3)
    block2.fail(InvalidCoinbaseReward)

    info("adjust the miningReward to account for the adjusted gas reward")
    block2.Coinbase.output(_.copy(amount = miningReward * 2)).pass()
  }

  it should "check non-empty txs" in new Fixture {
    val block = emptyBlock(blockFlow, chainIndex)
    block.copy(transactions = AVector.empty).fail(EmptyTransactionList)(checkNonEmptyTransactions)
  }

  it should "check the number of txs" in new Fixture {
    implicit val validator = checkTxNumber _

    val block   = transfer(blockFlow, chainIndex)
    val maxTxs  = AVector.fill(maximalTxsInOneBlock)(block.nonCoinbase.head)
    val moreTxs = block.nonCoinbase.head +: maxTxs

    block.copy(transactions = maxTxs).pass()
    block.copy(transactions = moreTxs).fail(TooManyTransactions)
    block.copy(transactions = moreTxs).fail(TooManyTransactions)(checkBlockUnit(_, blockFlow))
  }

  it should "check the gas price decreasing" in new Fixture {
    implicit val validator = checkGasPriceDecreasing _

    val block = transfer(blockFlow, chainIndex)
    val low   = block.nonCoinbase.head
    low.unsigned.gasPrice is defaultGasPrice

    val higherGas = GasPrice(defaultGasPrice.value + 1)
    val high      = low.copy(unsigned = low.unsigned.copy(gasPrice = higherGas))

    block.copy(transactions = AVector(high, low, low)).pass()
    block.copy(transactions = AVector(low, low, low)).pass()
    block.copy(transactions = AVector(low, high, low)).fail(TxGasPriceNonDecreasing)

    block
      .copy(transactions = AVector(low, high, low))
      .fail(TxGasPriceNonDecreasing)(checkBlockUnit(_, blockFlow))
  }

  it should "check the amount of gas" in new Fixture {
    implicit val validator = checkTotalGas _

    val block = transfer(blockFlow, chainIndex)
    val tx    = block.nonCoinbase.head
    tx.unsigned.gasAmount is minimalGas

    val higherGas   = GasBox.unsafe(minimalGas.value + 1)
    val higherGasTx = tx.copy(unsigned = tx.unsigned.copy(gasAmount = higherGas))

    val maxTxs = AVector.fill(maximalTxsInOneBlock)(tx)

    block.pass()
    block.copy(transactions = maxTxs).pass()
    block.copy(transactions = higherGasTx +: maxTxs.tail).fail(TooMuchGasUsed)

    block
      .copy(transactions = higherGasTx +: maxTxs.tail)
      .fail(TooMuchGasUsed)(checkBlockUnit(_, blockFlow))
  }

  it should "check double spending in a same tx" in new Fixture {
    val invalidTx = doubleSpendingTx(blockFlow, chainIndex)
    val block     = mine(blockFlow, chainIndex)((_, _) => AVector(invalidTx))

    block.fail(BlockDoubleSpending)(checkBlockDoubleSpending)
    block.fail(BlockDoubleSpending)(checkBlockUnit(_, blockFlow))
  }

  it should "check double spending in a same block" in new Fixture {
    implicit val validator = checkBlockDoubleSpending _
    val intraChainIndex    = ChainIndex.unsafe(0, 0)

    val block0 = transferOnlyForIntraGroup(blockFlow, intraChainIndex)
    block0.nonCoinbase.length is 1
    block0.pass()

    val block1 =
      mine(blockFlow, intraChainIndex)((_, _) => block0.nonCoinbase ++ block0.nonCoinbase)
    block1.nonCoinbase.length is 2
    block1.fail(BlockDoubleSpending)
    block1.fail(BlockDoubleSpending)(checkBlockUnit(_, blockFlow))
  }

  it should "check double spending when UTXOs are directly spent again" in new Fixture {
    forAll(chainIndexGenForBroker(brokerConfig)) { index =>
      val block0 = transfer(blockFlow, index)
      addAndCheck(blockFlow, block0)

      for (to <- 0 until brokerConfig.groups) {
        // UTXOs spent by `block0.nonCoinbase` can not be spent again
        val from  = block0.chainIndex.from.value
        val block = mine(blockFlow, ChainIndex.unsafe(from, to))((_, _) => block0.nonCoinbase)
        block.nonCoinbaseLength is 1
        checkFlow(block, blockFlow) is Left(Right(InvalidFlowTxs))
      }
    }
  }

  it should "check double spending in block dependencies" in new Fixture {
    val mainGroup = GroupIndex.unsafe(0)
    val block0    = transfer(blockFlow, ChainIndex.unsafe(mainGroup.value, 0))
    val block1    = transfer(blockFlow, ChainIndex.unsafe(mainGroup.value, 1))

    addAndCheck(blockFlow, block0, 1)
    addAndCheck(blockFlow, block1, 1)

    // block1 and block2 conflict with each other
    blockFlow.isConflicted(AVector(block1.hash, block0.hash), blockFlow.getBlockUnsafe) is true

    for (to <- 0 until brokerConfig.groups) {
      val index = ChainIndex.unsafe(mainGroup.value, to)
      val block = transfer(blockFlow, index)

      // Update outDeps to contain conflicting blocks: block1 and block2
      val newDeps = block.header.blockDeps.deps
        .replace(brokerConfig.groups - 1, block0.hash)
        .replace(brokerConfig.groups, block1.hash)

      val newBlock = mine(
        blockFlow,
        index,
        newDeps,
        block.transactions,
        block.header.timestamp
      )

      blockFlow.isConflicted(newBlock.header.outDeps, blockFlow.getBlockUnsafe) is true
      blockFlow.getHashesForDoubleSpendingCheckUnsafe(mainGroup, newBlock.blockDeps).toSet is
        Set(block0.hash, block1.hash)

      checkFlow(newBlock, blockFlow) is Left(Right(InvalidFlowTxs))
    }
  }

  it should "calculate all the hashes for double spending check when there are genesis deps" in new Fixture {
    val blockFlow1 = isolatedBlockFlow()

    val block1 = emptyBlock(blockFlow1, ChainIndex.unsafe(0, 0))
    addAndCheck(blockFlow1, block1)
    val block2 = emptyBlock(blockFlow1, ChainIndex.unsafe(0, 0))
    addAndCheck(blockFlow1, block2)
    val block3 = emptyBlock(blockFlow1, ChainIndex.unsafe(0, 2))
    addAndCheck(blockFlow1, block3)

    val block0 = emptyBlock(blockFlow, ChainIndex.unsafe(0, 1))
    addAndCheck(blockFlow, block0, block1, block2, block3)

    val mainGroup = GroupIndex.unsafe(0)
    val bestDeps  = blockFlow.getBestDeps(mainGroup)
    blockFlow.getHashesForDoubleSpendingCheckUnsafe(mainGroup, bestDeps).toSet is
      Set(block0.hash, block1.hash, block2.hash, block3.hash)

    val bestDeps1 = blockFlow1.getBestDeps(mainGroup)
    blockFlow1.getHashesForDoubleSpendingCheckUnsafe(mainGroup, bestDeps1).toSet is
      Set(block1.hash, block2.hash, block3.hash)
  }

  it should "calculate all the hashes for double spending check when there are no genesis deps" in new Fixture {
    val blockFlow1 = isolatedBlockFlow()
    val mainGroup  = GroupIndex.unsafe(0)
    (0 until groups0).reverse.foreach { toGroup =>
      val chainIndex = ChainIndex.unsafe(mainGroup.value, toGroup)
      val block      = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, block)
      addAndCheck(blockFlow1, block)
    }

    val block0 = emptyBlock(blockFlow, ChainIndex.unsafe(0, 1))
    val block1 = emptyBlock(blockFlow1, ChainIndex.unsafe(0, 0))
    val block2 = emptyBlock(blockFlow1, ChainIndex.unsafe(0, 2))

    addAndCheck(blockFlow, block0, block1, block2)

    val bestDeps = blockFlow.getBestDeps(mainGroup)

    blockFlow.getHashesForDoubleSpendingCheckUnsafe(mainGroup, bestDeps).toSet is
      Set(block0.hash, block1.hash, block2.hash)
  }

  it should "validate old blocks" in new Fixture {
    val block0     = transfer(blockFlow, chainIndex)
    val newBlockTs = ALF.LaunchTimestamp.plusSecondsUnsafe(10)
    val block1     = mineWithoutCoinbase(blockFlow, chainIndex, block0.nonCoinbase, newBlockTs)
    checkBlockUnit(block1, blockFlow) isE ()
  }
}
