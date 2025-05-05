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

import java.math.BigInteger

import scala.util.Random

import akka.util.ByteString
import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.crypto.Byte64
import org.alephium.flow.{FlowFixture, GhostUncleFixture}
import org.alephium.flow.core.{BlockFlow, FlowUtils}
import org.alephium.flow.gasestimation.GasEstimation
import org.alephium.flow.io.StoragesFixture
import org.alephium.protocol.{ALPH, Hash, PrivateKey, PublicKey, Signature, SignatureSchema}
import org.alephium.protocol.config._
import org.alephium.protocol.mining.{Emission, HashRate}
import org.alephium.protocol.model._
import org.alephium.protocol.vm
import org.alephium.protocol.vm.{BlockHash => _, NetworkId => _, _}
import org.alephium.serde.serialize
import org.alephium.util.{AlephiumSpec, AVector, Bytes, Duration, TimeStamp, U256}

// scalastyle:off file.size.limit
class BlockValidationSpec extends AlephiumSpec {

  trait Fixture extends BlockValidation with FlowFixture with NoIndexModelGeneratorsLike {
    lazy val chainIndex = chainIndexGenForBroker(brokerConfig).sample.value
    override def headerValidation: HeaderValidation  = HeaderValidation.build
    override def nonCoinbaseValidation: TxValidation = TxValidation.build

    implicit class RichBlock(block: Block) {
      object Coinbase {
        def unsignedTx(f: UnsignedTransaction => UnsignedTransaction): Block = {
          val updated = block.coinbase.copy(unsigned = f(block.coinbase.unsigned))
          block.copy(transactions = block.nonCoinbase :+ updated)
        }

        def tx(f: Transaction => Transaction): Block = {
          block.copy(transactions = block.nonCoinbase :+ f(block.coinbase))
        }

        def output(f: AssetOutput => AssetOutput, index: Int = 0): Block = {
          val outputs = block.coinbase.unsigned.fixedOutputs
          unsignedTx(_.copy(fixedOutputs = outputs.replace(index, f(outputs(index)))))
        }

        def input(f: TxInput => TxInput, index: Int): Block = {
          val inputs = block.coinbase.unsigned.inputs
          unsignedTx(_.copy(inputs = inputs.replace(index, f(inputs(index)))))
        }

        def gasAmount(f: GasBox => GasBox): Block = {
          tx(tx => tx.copy(unsigned = tx.unsigned.copy(gasAmount = f(tx.unsigned.gasAmount))))
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

    def sortGhostUncleHashes(hashes: AVector[BlockHash]): AVector[BlockHash] = {
      hashes.sortBy(_.bytes)(Bytes.byteStringOrdering)
    }
  }

  trait GenesisForkFixture extends Fixture {
    setHardFork(HardFork.Mainnet)
  }

  it should "validate group for block" in new Fixture {
    implicit val validator: (Block) => BlockValidationResult[Unit] = checkGroup _

    forAll(blockGenOf(brokerConfig))(_.pass())
    forAll(blockGenNotOf(brokerConfig))(_.fail(InvalidGroup))
  }

  it should "validate nonEmpty transaction list" in new Fixture {
    implicit val validator: (Block) => BlockValidationResult[Unit] = checkNonEmptyTransactions _

    forAll(blockGen) { block =>
      if (block.transactions.nonEmpty) {
        block.pass()
      }

      val withoutTx = block.copy(transactions = AVector.empty)
      withoutTx.fail(EmptyTransactionList)
    }
  }

  trait CoinbaseFormatFixture extends Fixture {
    val output0         = assetOutputGen.sample.get
    val emptyOutputs    = AVector.empty[AssetOutput]
    val emptySignatures = AVector.empty[Byte64]
    val script          = StatefulScript.alwaysFail
    val testSignatures  = AVector(Byte64.from(Signature.generate))
    val block           = emptyBlock(blockFlow, chainIndex)

    def commonTest(block: Block = block)(implicit
        validator: Block => BlockValidationResult[Unit],
        error: InvalidBlockStatus
    ) = {
      info("script")
      block.Coinbase.unsignedTx(_.copy(scriptOpt = None)).pass()
      block.Coinbase.unsignedTx(_.copy(scriptOpt = Some(script))).fail()

      info("gasPrice")
      block.Coinbase.unsignedTx(_.copy(gasPrice = coinbaseGasPrice)).pass()
      block.Coinbase.unsignedTx(_.copy(gasPrice = GasPrice(U256.Zero))).fail()

      info("output token")
      block.Coinbase.unsignedTx(_.copy(fixedOutputs = AVector(output0))).pass()
      val outputsWithTokens = AVector(output0.copy(tokens = AVector(TokenId.zero -> 10)))
      block.Coinbase.unsignedTx(_.copy(fixedOutputs = outputsWithTokens)).fail()

      info("contract input")
      block.Coinbase.tx(_.copy(contractInputs = AVector.empty)).pass()
      val invalidContractInputs = AVector(contractOutputRefGen(GroupIndex.unsafe(0)).sample.get)
      block.Coinbase.tx(_.copy(contractInputs = invalidContractInputs)).fail()

      info("generated output")
      block.Coinbase.tx(_.copy(generatedOutputs = emptyOutputs.as[TxOutput])).pass()
      block.Coinbase.tx(_.copy(generatedOutputs = AVector(output0))).fail()

      info("contract signature")
      block.Coinbase.tx(_.copy(scriptSignatures = emptySignatures)).pass()
      block.Coinbase.tx(_.copy(scriptSignatures = testSignatures)).fail()
    }
  }

  it should "validate coinbase transaction simple format" in new CoinbaseFormatFixture {
    implicit val validator: (Block) => BlockValidationResult[Unit] =
      (blk: Block) => checkCoinbaseEasy(blk, 0, false)
    implicit val error: InvalidBlockStatus = InvalidCoinbaseFormat

    commonTest(block)

    info("gasAmount")
    block.Coinbase.unsignedTx(_.copy(gasAmount = minimalGas)).pass()
    block.Coinbase.unsignedTx(_.copy(gasAmount = GasBox.from(0).value)).fail()

    info("output length")
    block.Coinbase.unsignedTx(_.copy(fixedOutputs = AVector(output0))).pass()
    block.Coinbase.unsignedTx(_.copy(fixedOutputs = emptyOutputs)).fail()

    info("input signature")
    block.Coinbase.tx(_.copy(inputSignatures = emptySignatures)).pass()
    block.Coinbase.tx(_.copy(inputSignatures = testSignatures)).fail()
  }

  it should "validate PoLW coinbase transaction simple format" in new CoinbaseFormatFixture {
    implicit val validator: (Block) => BlockValidationResult[Unit] =
      (blk: Block) => checkCoinbaseEasy(blk, 0, isPoLW = true)
    implicit val error: InvalidBlockStatus = InvalidPoLWCoinbaseFormat
    val inputs                             = AVector(txInputGen.sample.get)
    override val block = emptyBlock(blockFlow, chainIndex).Coinbase
      .tx(_.copy(inputSignatures = testSignatures))
      .Coinbase
      .unsignedTx(_.copy(inputs = inputs))

    commonTest(block)

    info("gasAmount")
    block.Coinbase.unsignedTx(_.copy(gasAmount = minimalGas)).pass()
    block.Coinbase.unsignedTx(_.copy(gasAmount = minimalGas.subUnsafe(GasBox.unsafe(1)))).fail()
    block.Coinbase.unsignedTx(_.copy(gasAmount = minimalGas.addUnsafe(GasBox.unsafe(1)))).pass()

    info("output length")
    block.Coinbase.unsignedTx(_.copy(fixedOutputs = AVector(output0))).pass()
    block.Coinbase.unsignedTx(_.copy(fixedOutputs = AVector(output0, output0))).pass()
    block.Coinbase.unsignedTx(_.copy(fixedOutputs = emptyOutputs)).fail()

    info("input size")
    block.Coinbase.unsignedTx(_.copy(inputs = AVector.empty)).fail()
    block.Coinbase.unsignedTx(_.copy(inputs = inputs ++ inputs)).pass()

    info("input signature")
    block.Coinbase.tx(_.copy(inputSignatures = emptySignatures)).fail()
    block.Coinbase.tx(_.copy(inputSignatures = testSignatures)).pass()
    block.Coinbase.tx(_.copy(inputSignatures = testSignatures ++ testSignatures)).fail()
  }

  trait PreRhoneForkFixture extends Fixture {
    setHardForkBefore(HardFork.Rhone)
  }

  trait CoinbaseDataFixture extends Fixture {
    def block: Block
    def selectedUncles: AVector[SelectedGhostUncle]
    lazy val coinbaseData: CoinbaseData =
      CoinbaseData.from(chainIndex, block.timestamp, selectedUncles, ByteString.empty)

    def wrongTimeStamp(ts: TimeStamp): ByteString = {
      serialize(CoinbaseData.from(chainIndex, ts, selectedUncles, ByteString.empty))
    }

    def wrongChainIndex(chainIndex: ChainIndex): ByteString = {
      serialize(
        CoinbaseData.from(
          chainIndex,
          block.header.timestamp,
          selectedUncles,
          ByteString.empty
        )
      )
    }

    implicit val validator: Block => BlockValidationResult[Unit] =
      (blk: Block) => {
        val hardFork = networkConfig.getHardFork(blk.timestamp)
        checkCoinbaseData(blk.chainIndex, blk, hardFork).map(_ => ())
      }

    def testWrongBlockTimeStamp() = {
      info("wrong block timestamp")
      val data = wrongTimeStamp(block.timestamp.plusSecondsUnsafe(5))
      block.Coinbase.output(_.copy(additionalData = data)).fail(InvalidCoinbaseData)
    }

    def testWrongChainIndex() = {
      info("wrong chain index")
      val data = wrongChainIndex(chainIndexGen.retryUntil(_ != chainIndex).sample.get)
      block.Coinbase.output(_.copy(additionalData = data)).fail(InvalidCoinbaseData)
    }

    def testWrongFormat() = {
      info("wrong format")
      val wrongFormat = ByteString("wrong-coinbase-data-format")
      block.Coinbase.output(_.copy(additionalData = wrongFormat)).fail(InvalidCoinbaseData)
    }

    def testEmptyFixedOutputs() = {
      info("empty fixed outputs")
      block.Coinbase
        .unsignedTx(unsigned => unsigned.copy(fixedOutputs = AVector.empty))
        .fail(InvalidCoinbaseFormat)
    }
  }

  it should "check coinbase data for pre-rhone hardfork" in new CoinbaseDataFixture {
    setHardForkBefore(HardFork.Rhone)
    val block          = emptyBlock(blockFlow, chainIndex)
    val selectedUncles = AVector.empty

    coinbaseData is a[CoinbaseDataV1]
    block.coinbase.unsigned.fixedOutputs.head.additionalData is serialize(coinbaseData)
    block.pass()

    testWrongBlockTimeStamp()
    testWrongChainIndex()
    testWrongFormat()
    testEmptyFixedOutputs()
  }

  it should "check uncles for pre-rhone hardfork" in new Fixture {
    setHardForkBefore(HardFork.Rhone)
    val ghostUncles = AVector.fill(ALPH.MaxGhostUncleSize)(
      GhostUncleData(BlockHash.random, assetLockupGen(chainIndex.to).sample.get)
    )

    implicit val validator: (Block) => BlockValidationResult[Unit] = (blk: Block) => {
      checkGhostUncles(blockFlow, blk.chainIndex, blk, ghostUncles).map(_ => ())
    }

    val block = emptyBlock(blockFlow, chainIndex)
    block.fail(InvalidGhostUnclesBeforeRhoneHardFork)
  }

  trait CoinbaseDataWithGhostUnclesFixture extends CoinbaseDataFixture {
    lazy val uncleBlock     = blockGen(chainIndex).sample.get
    lazy val uncleMiner     = assetLockupGen(chainIndex.to).sample.get
    lazy val selectedUncles = AVector(SelectedGhostUncle(uncleBlock.hash, uncleMiner, 1))
    lazy val block = mineWithoutCoinbase(
      blockFlow,
      chainIndex,
      AVector.empty,
      TimeStamp.now(),
      selectedUncles
    )

    def testEmptyUncles() = {
      info("empty uncles")
      val block = emptyBlock(blockFlow, chainIndex)
      block.ghostUncleHashes.rightValue.isEmpty is true
      block.pass()
    }
  }

  it should "check coinbase data for rhone hardfork" in new CoinbaseDataWithGhostUnclesFixture {
    setHardFork(HardFork.Rhone)
    coinbaseData is a[CoinbaseDataV2]
    block.coinbase.unsigned.fixedOutputs.head.additionalData is serialize(coinbaseData)
    block.pass()

    testEmptyUncles()
    testWrongBlockTimeStamp()
    testWrongChainIndex()
    testWrongFormat()
    testEmptyFixedOutputs()
  }

  it should "check coinbase data for since-danube hardfork" in new CoinbaseDataWithGhostUnclesFixture {
    override val configValues: Map[String, Any] = Map(
      (
        "alephium.network.danube-hard-fork-timestamp",
        NetworkConfigFixture.SinceDanube.danubeHardForkTimestamp.millis
      )
    )
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Danube

    coinbaseData is a[CoinbaseDataV3]
    block.coinbase.unsigned.fixedOutputs.head.additionalData is serialize(coinbaseData)
    block.pass()

    testEmptyUncles()
    testWrongChainIndex()
    testWrongFormat()
    testEmptyFixedOutputs()

    info("wrong block timestamp")
    val data0 = wrongTimeStamp(block.timestamp.plusSecondsUnsafe(5))
    block.Coinbase.output(_.copy(additionalData = data0)).pass()
  }

  it should "check coinbase locked amount pre-rhone" in new Fixture {
    setHardForkBefore(HardFork.Rhone)
    val block           = emptyBlock(blockFlow, chainIndex)
    val consensusConfig = consensusConfigs.getConsensusConfig(block.timestamp)
    val miningReward    = consensusConfig.emission.reward(block.header).miningReward
    val lockedAmount    = miningReward
    implicit val validator: (Block) => BlockValidationResult[Unit] =
      (blk: Block) => checkLockedReward(blk, AVector(lockedAmount))

    info("valid")
    block.pass()

    info("invalid locked amount")
    block.Coinbase.output(_.copy(amount = U256.One)).fail(InvalidCoinbaseLockedAmount)

    info("invalid lockup period")
    block.Coinbase.output(_.copy(lockTime = TimeStamp.now())).fail(InvalidCoinbaseLockupPeriod)
  }

  it should "check coinbase reward pre-rhone" in new Fixture {
    setHardForkBefore(HardFork.Rhone)
    val block = emptyBlock(blockFlow, chainIndex)
    implicit val validator: (Block) => BlockValidationResult[Unit] = (blk: Block) => {
      val groupView = blockFlow.getMutableGroupViewPreDanube(blk).rightValue
      checkCoinbase(blockFlow, blk.chainIndex, blk, groupView, HardFork.Leman)
    }

    val consensusConfig = consensusConfigs.getConsensusConfig(block.timestamp)
    val miningReward    = consensusConfig.emission.reward(block.header).miningReward
    block.Coinbase.output(_.copy(amount = miningReward)).pass()

    val invalidMiningReward = miningReward.subUnsafe(1)
    block.Coinbase.output(_.copy(amount = invalidMiningReward)).fail(InvalidCoinbaseReward)
  }

  it should "check gas reward cap for Genesis fork" in new GenesisForkFixture {

    implicit val validator: (Block) => BlockValidationResult[Unit] = (blk: Block) => {
      val groupView = blockFlow.getMutableGroupViewPreDanube(blk).rightValue
      checkCoinbase(blockFlow, blk.chainIndex, blk, groupView, HardFork.Mainnet)
    }

    val block0 = transfer(blockFlow, chainIndex)
    block0.pass()

    info("gas reward is set to 1/2 of miningReward, miningReward not enough")
    val consensusConfig = consensusConfigs.getConsensusConfig(block0.timestamp)
    val miningReward    = consensusConfig.emission.reward(block0.header).miningReward
    val block1          = block0.replaceTxGas(miningReward)
    block1.fail(InvalidCoinbaseReward)

    info("adjust the miningReward to account for the adjusted gas reward")
    block1.Coinbase.output(_.copy(amount = miningReward + (block1.gasFee / 2))).pass()

    info("gas reward is set to the max: the same as miningReward, miningReward not enough")
    val block2 = block0.replaceTxGas(miningReward * 3)
    block2.fail(InvalidCoinbaseReward)

    info("adjust the miningReward to account for the adjusted gas reward")
    block2.Coinbase.output(_.copy(amount = miningReward * 2)).pass()
  }

  it should "check gas reward for Leman fork" in new Fixture {
    setHardFork(HardFork.Leman)
    implicit val validator: (Block) => BlockValidationResult[Unit] = (blk: Block) => {
      val groupView = blockFlow.getMutableGroupViewPreDanube(blk).rightValue
      checkCoinbase(blockFlow, blk.chainIndex, blk, groupView, HardFork.Leman)
    }

    val block = transfer(blockFlow, chainIndex)
    block.pass()

    val consensusConfig = consensusConfigs.getConsensusConfig(block.timestamp)
    val miningReward    = consensusConfig.emission.reward(block.header).miningReward
    block.coinbase.unsigned.fixedOutputs.head.amount is miningReward
  }

  it should "check non-empty txs" in new Fixture {
    val block = emptyBlock(blockFlow, chainIndex)
    block.copy(transactions = AVector.empty).fail(EmptyTransactionList)(checkNonEmptyTransactions)
  }

  it should "check the number of txs" in new Fixture {
    implicit val validator: (Block) => BlockValidationResult[Unit] = checkTxNumber _

    val block   = transfer(blockFlow, chainIndex)
    val maxTxs  = AVector.fill(maximalTxsInOneBlock)(block.nonCoinbase.head)
    val moreTxs = block.nonCoinbase.head +: maxTxs

    block.copy(transactions = maxTxs).pass()
    block.copy(transactions = moreTxs).fail(TooManyTransactions)
    block.copy(transactions = moreTxs).fail(TooManyTransactions)(checkBlockUnit(_, blockFlow))
  }

  it should "check the gas price decreasing" in new Fixture {
    implicit val validator: (Block) => BlockValidationResult[Unit] = checkGasPriceDecreasing _

    val block = transfer(blockFlow, chainIndex)
    val low   = block.nonCoinbase.head
    low.unsigned.gasPrice is nonCoinbaseMinGasPrice

    val higherGas = GasPrice(nonCoinbaseMinGasPrice.value + 1)
    val high      = low.copy(unsigned = low.unsigned.copy(gasPrice = higherGas))

    block.copy(transactions = AVector(high, low, low)).pass()
    block.copy(transactions = AVector(low, low, low)).pass()
    block.copy(transactions = AVector(low, high, low)).fail(TxGasPriceNonDecreasing)

    block
      .copy(transactions = AVector(low, high, low))
      .fail(TxGasPriceNonDecreasing)(checkBlockUnit(_, blockFlow))
  }

  it should "check the amount of gas" in new Fixture {
    val block = transfer(blockFlow, chainIndex)
    val tx    = block.nonCoinbase.head
    tx.unsigned.gasAmount is minimalGas

    def test(txsNum: Int)(implicit validator: Block => BlockValidationResult[Unit]) = {
      val higherGas   = GasBox.unsafe(minimalGas.value + 1)
      val higherGasTx = tx.copy(unsigned = tx.unsigned.copy(gasAmount = higherGas))

      val maxTxs = AVector.fill(txsNum)(tx)

      block.pass()
      block.copy(transactions = maxTxs).pass()
      block.copy(transactions = higherGasTx +: maxTxs.tail).fail(TooMuchGasUsed)

      block
        .copy(transactions = higherGasTx +: maxTxs.tail)
        .fail(TooMuchGasUsed)(checkBlockUnit(_, blockFlow))
    }

    val preRhoneValidator = checkTotalGas(_, HardFork.PreRhoneForTest)
    test(maximalTxsInOneBlock)(preRhoneValidator)
    val rhoneValidator = checkTotalGas(_, HardFork.Rhone)
    test(maximalTxsInOneBlock / 4)(rhoneValidator)
  }

  it should "check double spending in a same tx" in new Fixture {
    val invalidTx = doubleSpendingTx(blockFlow, chainIndex)
    val block     = mineWithTxs(blockFlow, chainIndex)((_, _) => AVector(invalidTx))

    block.fail(BlockDoubleSpending)(checkBlockDoubleSpending)
    block.fail(BlockDoubleSpending)(checkBlockUnit(_, blockFlow))
  }

  it should "check double spending in a same block" in new Fixture {
    implicit val validator: (Block) => BlockValidationResult[Unit] = checkBlockDoubleSpending _
    val intraChainIndex                                            = ChainIndex.unsafe(0, 0)

    val block0 = transferOnlyForIntraGroup(blockFlow, intraChainIndex)
    block0.nonCoinbase.length is 1
    block0.pass()

    val block1 =
      mineWithTxs(blockFlow, intraChainIndex)((_, _) => block0.nonCoinbase ++ block0.nonCoinbase)
    block1.nonCoinbase.length is 2
    block1.fail(BlockDoubleSpending)
    block1.fail(BlockDoubleSpending)(checkBlockUnit(_, blockFlow))
  }

  it should "check double spending when UTXOs are directly spent again" in new Fixture {
    setHardForkBefore(HardFork.Danube)
    forAll(chainIndexGenForBroker(brokerConfig)) { index =>
      val block0 = transfer(blockFlow, index)
      addAndCheck(blockFlow, block0)

      for (to <- 0 until brokerConfig.groups) {
        // UTXOs spent by `block0.nonCoinbase` can not be spent again
        val from = block0.chainIndex.from.value
        val block =
          mineWithTxs(blockFlow, ChainIndex.unsafe(from, to))((_, _) => block0.nonCoinbase)
        block.nonCoinbaseLength is 1
        checkFlow(block, blockFlow, HardFork.Rhone) is Left(Right(InvalidFlowTxs))
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

      checkFlow(newBlock, blockFlow, HardFork.Rhone) is Left(Right(InvalidFlowTxs))
    }
  }

  it should "calculate all the hashes for double spending check when there are genesis deps" in new Fixture {
    setHardForkBefore(HardFork.Danube)
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
    val bestDeps  = blockFlow.getBestDepsPreDanube(mainGroup)
    blockFlow.getHashesForDoubleSpendingCheckUnsafe(mainGroup, bestDeps).toSet is
      Set(block0.hash, block1.hash, block2.hash, block3.hash)

    val bestDeps1 = blockFlow1.getBestDepsPreDanube(mainGroup)
    blockFlow1.getHashesForDoubleSpendingCheckUnsafe(mainGroup, bestDeps1).toSet is
      Set(block1.hash, block2.hash, block3.hash)
  }

  it should "calculate all the hashes for double spending check when there are no genesis deps" in new Fixture {
    setHardForkBefore(HardFork.Danube)
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

    val bestDeps = blockFlow.getBestDepsPreDanube(mainGroup)

    blockFlow.getHashesForDoubleSpendingCheckUnsafe(mainGroup, bestDeps).toSet is
      Set(block0.hash, block1.hash, block2.hash)
  }

  it should "validate old blocks" in new GenesisForkFixture {
    val block0     = transfer(blockFlow, chainIndex)
    val newBlockTs = ALPH.LaunchTimestamp.plusSecondsUnsafe(10)
    val block1     = mineWithoutCoinbase(blockFlow, chainIndex, block0.nonCoinbase, newBlockTs)
    checkBlockUnit(block1, blockFlow) isE ()
  }

  it should "invalidate blocks with breaking instrs" in new Fixture {
    setHardFork(HardFork.Leman)
    override lazy val chainIndex: ChainIndex = ChainIndex.unsafe(0, 0)
    val newStorages = StoragesFixture.buildStorages(rootPath.resolve(Hash.generate.toHexString))
    val genesisNetworkConfig = new NetworkConfigFixture.Default {
      override def lemanHardForkTimestamp: TimeStamp  = TimeStamp.now().plusHoursUnsafe(1)
      override def rhoneHardForkTimestamp: TimeStamp  = TimeStamp.Max
      override def danubeHardForkTimestamp: TimeStamp = TimeStamp.Max
    }.networkConfig
    val lemanNetworkConfig = new NetworkConfigFixture.Default {
      override def rhoneHardForkTimestamp: TimeStamp  = TimeStamp.Max
      override def danubeHardForkTimestamp: TimeStamp = TimeStamp.Max
    }.networkConfig

    genesisNetworkConfig.getHardFork(TimeStamp.now()) is HardFork.Mainnet
    lemanNetworkConfig.getHardFork(TimeStamp.now()) is HardFork.Leman
    val blockflowGenesis = BlockFlow.fromGenesisUnsafe(newStorages, config.genesisBlocks)(
      implicitly,
      genesisNetworkConfig,
      blockFlow.consensusConfigs,
      implicitly,
      implicitly
    )
    val blockflowLeman = BlockFlow.fromGenesisUnsafe(newStorages, config.genesisBlocks)(
      implicitly,
      lemanNetworkConfig,
      blockFlow.consensusConfigs,
      implicitly,
      implicitly
    )
    val script =
      StatefulScript.unsafe(
        AVector(Method.testDefault(true, 0, 0, 0, AVector(vm.TxGasFee)))
      )
    val block = simpleScript(blockflowLeman, chainIndex, script)
    intercept[AssertionError](simpleScript(blockflowGenesis, chainIndex, script)).getMessage is
      s"Right(TxScriptExeFailed(${vm.InactiveInstr(vm.TxGasFee)}))"

    val validatorGenesis = BlockValidation.build(blockflowGenesis)
    val validatorLeman   = BlockValidation.build(blockflowLeman)
    validatorGenesis.validate(block, blockflowGenesis).leftValue isE ExistInvalidTx(
      block.nonCoinbase.head,
      UsingBreakingInstrs
    )
    validatorLeman.validate(block, blockflowLeman).isRight is true
  }

  trait SinceRhoneCoinbaseFixture extends SinceRhoneGhostUncleFixture {
    lazy val hardFork = networkConfig.getHardFork(TimeStamp.now())

    def randomLockupScript: LockupScript.Asset = {
      val (_, toPublicKey) = chainIndex.to.generateKey
      LockupScript.p2pkh(toPublicKey)
    }

    def getMiningReward(block: Block): U256 = {
      val consensusConfig = consensusConfigs.getConsensusConfig(block.timestamp)
      consensusConfig.emission.reward(block.header).miningReward
    }

    private def mineBlock(uncleSize: Int): (Block, AVector[SelectedGhostUncle]) = {
      val uncleBlockHashes = mineUncleBlocks(uncleSize)
      val template         = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
      val block            = mine(blockFlow, template)
      block.ghostUncleHashes.rightValue.toSet is uncleBlockHashes.toSet
      val uncles = block.ghostUncleHashes.rightValue.map { uncleHash =>
        getSelectedGhostUncle(template.height, uncleHash)
      }
      (block, uncles)
    }

    def mineBlockWith1Uncle(): (Block, AVector[SelectedGhostUncle]) = mineBlock(1)
    def mineBlockWith2Uncle(): (Block, AVector[SelectedGhostUncle]) = mineBlock(2)
  }

  it should "check coinbase locked amount since rhone" in new SinceRhoneCoinbaseFixture {
    {
      info("block has no uncle")
      val block           = emptyBlock(blockFlow, chainIndex)
      val miningReward    = getMiningReward(block)
      val mainChainReward = Coinbase.calcMainChainRewardSinceRhone(hardFork, miningReward)
      val lockedReward    = mainChainReward
      implicit val validator: (Block) => BlockValidationResult[Unit] =
        (blk: Block) => checkLockedReward(blk, AVector(lockedReward))

      info("valid")
      block.pass()

      info("invalid locked amount")
      block.Coinbase.output(_.copy(amount = U256.One)).fail(InvalidCoinbaseLockedAmount)
      block.Coinbase.output(_.copy(amount = miningReward)).fail(InvalidCoinbaseLockedAmount)

      info("invalid lockup period")
      block.Coinbase.output(_.copy(lockTime = TimeStamp.now())).fail(InvalidCoinbaseLockupPeriod)
    }

    {
      info("block has 1 uncle")
      val (block, ghostUncles) = mineBlockWith1Uncle()
      val miningReward         = getMiningReward(block)
      val mainChainReward      = Coinbase.calcMainChainRewardSinceRhone(hardFork, miningReward)
      val uncleReward = Coinbase.calcGhostUncleReward(mainChainReward, ghostUncles.head.heightDiff)
      val blockReward = mainChainReward.addUnsafe(uncleReward.divUnsafe(32))
      implicit val validator: (Block) => BlockValidationResult[Unit] =
        (blk: Block) => checkLockedReward(blk, AVector(blockReward, uncleReward))

      info("valid")
      block.pass()

      info("invalid locked amount")
      block.Coinbase
        .output(_.copy(amount = blockReward.addOneUnsafe()))
        .fail(InvalidCoinbaseLockedAmount)
      block.Coinbase
        .output(_.copy(amount = uncleReward.addOneUnsafe()), 1)
        .fail(InvalidCoinbaseLockedAmount)

      info("invalid lockup period")
      block.Coinbase.output(_.copy(lockTime = TimeStamp.now())).fail(InvalidCoinbaseLockupPeriod)
      block.Coinbase.output(_.copy(lockTime = TimeStamp.now()), 1).fail(InvalidCoinbaseLockupPeriod)
    }

    {
      info("block has 2 uncles")
      val (block, ghostUncles) = mineBlockWith2Uncle()
      val miningReward         = getMiningReward(block)
      val mainChainReward      = Coinbase.calcMainChainRewardSinceRhone(hardFork, miningReward)
      val uncleReward0 = Coinbase.calcGhostUncleReward(mainChainReward, ghostUncles.head.heightDiff)
      val uncleReward1 = Coinbase.calcGhostUncleReward(mainChainReward, ghostUncles.last.heightDiff)
      val blockReward =
        mainChainReward.addUnsafe(uncleReward0.addUnsafe(uncleReward1).divUnsafe(32))
      implicit val validator =
        (blk: Block) => checkLockedReward(blk, AVector(blockReward, uncleReward0, uncleReward1))

      info("valid")
      block.pass()

      info("invalid locked amount")
      block.Coinbase
        .output(_.copy(amount = blockReward.addOneUnsafe()))
        .fail(InvalidCoinbaseLockedAmount)
      block.Coinbase
        .output(_.copy(amount = uncleReward0.addOneUnsafe()), 1)
        .fail(InvalidCoinbaseLockedAmount)
      block.Coinbase
        .output(_.copy(amount = uncleReward1.addOneUnsafe()), 2)
        .fail(InvalidCoinbaseLockedAmount)

      info("invalid lockup period")
      block.Coinbase.output(_.copy(lockTime = TimeStamp.now())).fail(InvalidCoinbaseLockupPeriod)
      block.Coinbase.output(_.copy(lockTime = TimeStamp.now()), 1).fail(InvalidCoinbaseLockupPeriod)
      block.Coinbase.output(_.copy(lockTime = TimeStamp.now()), 2).fail(InvalidCoinbaseLockupPeriod)
    }
  }

  it should "check coinbase reward since rhone" in new SinceRhoneCoinbaseFixture {
    implicit val validator: (Block) => BlockValidationResult[Unit] = (blk: Block) => {
      val groupView = blockFlow.getMutableGroupView(blk.chainIndex.from).rightValue
      checkCoinbase(blockFlow, blk.chainIndex, blk, groupView, hardFork)
    }

    {
      info("block has no uncle")
      val block = emptyBlock(blockFlow, chainIndex)

      info("valid")
      block.pass()

      val miningReward    = getMiningReward(block)
      val mainChainReward = Coinbase.calcMainChainRewardSinceRhone(hardFork, miningReward)

      info("invalid block reward")
      block.Coinbase.output(_.copy(amount = miningReward)).fail(InvalidCoinbaseReward)
      block.Coinbase
        .output(_.copy(amount = mainChainReward.subOneUnsafe()))
        .fail(InvalidCoinbaseReward)
    }

    {
      info("block has 1 uncle")
      val (block, ghostUncles) = mineBlockWith1Uncle()
      val miningReward         = getMiningReward(block)
      val mainChainReward      = Coinbase.calcMainChainRewardSinceRhone(hardFork, miningReward)
      val uncleReward = Coinbase.calcGhostUncleReward(mainChainReward, ghostUncles.head.heightDiff)

      info("valid")
      block.pass()

      info("invalid reward amount")
      block.Coinbase.output(_.copy(amount = miningReward)).fail(InvalidCoinbaseReward)
      block.Coinbase.output(_.copy(amount = uncleReward)).fail(InvalidCoinbaseReward)
      block.Coinbase
        .output(_.copy(amount = uncleReward.subOneUnsafe()), 1)
        .fail(InvalidCoinbaseReward)

      info("invalid uncle miner lockup script")
      block.Coinbase
        .output(_.copy(lockupScript = randomLockupScript), 1)
        .fail(InvalidGhostUncleMiner)
    }

    {
      info("block has 2 uncles")
      val (block, ghostUncles) = mineBlockWith2Uncle()
      val miningReward         = getMiningReward(block)
      val mainChainReward      = Coinbase.calcMainChainRewardSinceRhone(hardFork, miningReward)
      val uncleReward0 = Coinbase.calcGhostUncleReward(mainChainReward, ghostUncles.head.heightDiff)
      val uncleReward1 = Coinbase.calcGhostUncleReward(mainChainReward, ghostUncles.last.heightDiff)

      info("valid")
      block.pass()

      info("invalid reward amount")
      block.Coinbase
        .output(_.copy(amount = miningReward))
        .fail(InvalidCoinbaseReward)
      block.Coinbase
        .output(_.copy(amount = uncleReward0.addOneUnsafe()), 1)
        .fail(InvalidCoinbaseReward)
      block.Coinbase
        .output(_.copy(amount = uncleReward1.addOneUnsafe()), 2)
        .fail(InvalidCoinbaseReward)

      info("invalid uncle miner lockup script")
      block.Coinbase
        .output(_.copy(lockupScript = randomLockupScript), 1)
        .fail(InvalidGhostUncleMiner)
      block.Coinbase
        .output(_.copy(lockupScript = randomLockupScript), 2)
        .fail(InvalidGhostUncleMiner)
    }
  }

  trait SinceRhoneGhostUncleFixture extends Fixture with GhostUncleFixture {
    setHardForkSince(HardFork.Rhone)
    lazy val miner = getGenesisLockupScript(chainIndex.to)

    def mineUncleBlocks(uncleSize: Int): AVector[BlockHash] = {
      mineUncleBlocks(blockFlow, chainIndex, uncleSize)
    }

    def getSelectedGhostUncle(blockHeight: Int, uncleHash: BlockHash) = {
      val uncleBlock      = blockFlow.getBlockUnsafe(uncleHash)
      val uncleBlockMiner = uncleBlock.minerLockupScript
      val heightDiff      = blockHeight - blockFlow.getHeightUnsafe(uncleBlock.hash)
      SelectedGhostUncle(uncleHash, uncleBlockMiner, heightDiff)
    }
  }

  it should "invalidate block with invalid uncles size" in new SinceRhoneGhostUncleFixture {
    val ghostUncleHashes = mineUncleBlocks(ALPH.MaxGhostUncleSize + 1)
    val blockTemplate    = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    blockTemplate.ghostUncleHashes.length is ALPH.MaxGhostUncleSize
    ghostUncleHashes.length is ALPH.MaxGhostUncleSize + 1
    val block = mine(
      blockFlow,
      blockTemplate.setGhostUncles(
        ghostUncleHashes.map(hash => SelectedGhostUncle(hash, miner, 1))
      )
    )
    checkBlock(block, blockFlow).left.value isE InvalidGhostUncleSize
  }

  it should "invalidate block with unsorted uncles" in new SinceRhoneGhostUncleFixture {
    mineUncleBlocks(ALPH.MaxGhostUncleSize)
    val blockTemplate = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    blockTemplate.ghostUncleHashes.length is 2

    def rebuildTemplate(uncles: AVector[SelectedGhostUncle]) = {
      val newTemplate = blockTemplate.setGhostUncles(uncles)
      val coinbaseData =
        CoinbaseData.from(chainIndex, newTemplate.templateTs, uncles, ByteString.empty)
      val coinbase = newTemplate.transactions.last
      val assetOutput =
        coinbase.unsigned.fixedOutputs.head.copy(additionalData = serialize(coinbaseData))
      val unsigned = coinbase.unsigned.copy(
        fixedOutputs = coinbase.unsigned.fixedOutputs.replace(0, assetOutput)
      )
      newTemplate.copy(transactions = AVector(coinbase.copy(unsigned = unsigned)))
    }

    val block0 = mine(blockFlow, blockTemplate)
    checkBlock(block0, blockFlow).isRight is true

    val selectedUncle =
      getSelectedGhostUncle(blockTemplate.height, blockTemplate.ghostUncleHashes.head)
    val block1 = mine(blockFlow, rebuildTemplate(AVector(selectedUncle)))
    checkBlock(block1, blockFlow).isRight is true

    val block2 = mine(blockFlow, rebuildTemplate(AVector.fill(2)(selectedUncle)))
    checkBlock(block2, blockFlow).left.value isE UnsortedGhostUncles

    val invalidGhostUncles =
      blockTemplate.ghostUncleHashes.reverse.map(getSelectedGhostUncle(blockTemplate.height, _))
    val block3 = mine(blockFlow, rebuildTemplate(invalidGhostUncles))
    checkBlock(block3, blockFlow).left.value isE UnsortedGhostUncles
  }

  it should "invalidate block if the uncle miner is invalid" in new SinceRhoneGhostUncleFixture {
    mineUncleBlocks(ALPH.MaxGhostUncleSize)
    val blockTemplate = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    blockTemplate.ghostUncleHashes.length is 2

    val index = Random.nextInt(2)
    val ghostUncles = blockTemplate.ghostUncleHashes.mapWithIndex { case (hash, i) =>
      val uncleMiner = if (index == i) assetLockupGen(chainIndex.to).sample.get else miner
      SelectedGhostUncle(hash, uncleMiner, 1)
    }
    val block = mine(blockFlow, blockTemplate.setGhostUncles(ghostUncles))
    checkBlock(block, blockFlow).left.value isE InvalidGhostUncleMiner
  }

  it should "invalidate block with used uncles" in new SinceRhoneGhostUncleFixture {
    mineUncleBlocks(ALPH.MaxGhostUncleSize)
    val block0 = mineBlockTemplate(blockFlow, chainIndex)
    addAndCheck(blockFlow, block0)

    val block1Template = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    block1Template.ghostUncleHashes.isEmpty is true
    val usedGhostUncleHash = block0.ghostUncleHashes.rightValue.head
    val block1 = mine(
      blockFlow,
      block1Template.setGhostUncles(
        AVector(getSelectedGhostUncle(block1Template.height, usedGhostUncleHash))
      )
    )
    checkBlock(block1, blockFlow).left.value isE GhostUnclesAlreadyUsed
  }

  it should "invalidate block if uncle is sibling" in new SinceRhoneGhostUncleFixture {
    val block0 = emptyBlockWithMiner(blockFlow, chainIndex, miner)
    addAndCheck(blockFlow, block0)
    val block10 = emptyBlockWithMiner(blockFlow, chainIndex, miner)
    block10.parentHash is block0.hash

    val blockTemplate = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    val block11 =
      mine(
        blockFlow,
        blockTemplate.setGhostUncles(AVector(SelectedGhostUncle(block10.hash, miner, 1)))
      )
    block11.parentHash is block0.hash
    checkBlock(block11, blockFlow).left.value isE GhostUncleDoesNotExist(block10.hash)

    addAndCheck(blockFlow, block10)
    checkBlock(block11, blockFlow).left.value isE GhostUncleHashConflictWithParentHash
  }

  it should "invalidate block if uncle is ancestor" in new SinceRhoneGhostUncleFixture {
    val block0 = emptyBlockWithMiner(blockFlow, chainIndex, miner)
    addAndCheck(blockFlow, block0)

    var parentBlock = block0
    (0 until ALPH.MaxGhostUncleAge).foreach { _ =>
      val blockTemplate = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
      val invalidBlock =
        mine(
          blockFlow,
          blockTemplate.setGhostUncles(AVector(SelectedGhostUncle(block0.hash, miner, 1)))
        )
      invalidBlock.parentHash is parentBlock.hash
      checkBlock(invalidBlock, blockFlow).left.value isE NotGhostUnclesForTheBlock

      parentBlock = emptyBlockWithMiner(blockFlow, chainIndex, miner)
      addAndCheck(blockFlow, parentBlock)
    }

    addAndCheck(blockFlow, emptyBlockWithMiner(blockFlow, chainIndex, miner))
    (blockFlow.getMaxHeightByWeight(chainIndex).rightValue -
      blockFlow.getHeightUnsafe(block0.hash)) > ALPH.MaxGhostUncleAge is true
    val blockTemplate = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    val invalidBlock =
      mine(
        blockFlow,
        blockTemplate.setGhostUncles(AVector(SelectedGhostUncle(block0.hash, miner, 1)))
      )
    checkBlock(invalidBlock, blockFlow).left.value isE GhostUncleHashConflictWithParentHash
  }

  it should "invalidate block if uncles' parent is not from mainchain" in new SinceRhoneGhostUncleFixture {
    val uncleHash  = mineUncleBlocks(1).head
    val uncleBlock = blockFlow.getBlockUnsafe(uncleHash)
    val block0     = blockFlow.getBlockUnsafe(uncleBlock.parentHash)
    val block1     = mine(blockFlow, chainIndex, block0.blockDeps)
    addAndCheck(blockFlow, block1)
    val index        = brokerConfig.groups - 1 + chainIndex.to.value
    val deps         = uncleBlock.blockDeps.deps.replace(index, block1.hash)
    val invalidUncle = mine(blockFlow, chainIndex, BlockDeps.unsafe(deps))
    addAndCheck(blockFlow, invalidUncle)

    val blockTemplate = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    val block = mine(blockFlow, blockTemplate.setGhostUncles(blockFlow, AVector(invalidUncle.hash)))
    checkBlock(block, blockFlow).left.value isE GhostUncleHashConflictWithParentHash
  }

  it should "allow duplicate ghost uncles before danube" in new Fixture with GhostUncleFixture {
    setHardFork(HardFork.Rhone)
    mineBlocks(blockFlow, chainIndex, ALPH.MaxGhostUncleAge)

    val miner                      = getGenesisLockupScript(chainIndex.to)
    val uncleHeight                = nextInt(1, ALPH.MaxGhostUncleAge - 1)
    val (ghostUncle0, ghostUncle1) = mineTwoGhostUnclesAt(blockFlow, chainIndex, uncleHeight)
    val template0                  = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    template0.ghostUncleHashes.contains(ghostUncle0.hash) is true
    template0.ghostUncleHashes.contains(ghostUncle1.hash) is true
    val block0 = mine(blockFlow, template0)
    checkBlock(block0, blockFlow).isRight is true

    val ghostUncle2 = mineDuplicateGhostUncleBlockAt(blockFlow, chainIndex, uncleHeight)
    BlockHeader.fromSameTemplate(ghostUncle0.header, ghostUncle2.header) is true
    val uncleHashes = sortGhostUncleHashes(AVector(ghostUncle0.hash, ghostUncle2.hash))
    val template1   = template0.setGhostUncles(blockFlow, uncleHashes)
    val block1      = mine(blockFlow, template1)
    checkBlock(block0, blockFlow).isRight is true

    addAndCheck(blockFlow, block0, block1)
  }

  it should "invalidate block if there are duplicate ghost uncles since danube" in new Fixture
    with GhostUncleFixture {
    setHardForkSince(HardFork.Danube)
    mineBlocks(blockFlow, chainIndex, ALPH.MaxGhostUncleAge)

    val uncleHeight0               = nextInt(1, ALPH.MaxGhostUncleAge - 1)
    val (ghostUncle0, ghostUncle1) = mineTwoGhostUnclesAt(blockFlow, chainIndex, uncleHeight0)
    val miner                      = getGenesisLockupScript(chainIndex.to)
    val blockTemplate0             = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    blockTemplate0.ghostUncleHashes.contains(ghostUncle0.hash) is false
    blockTemplate0.ghostUncleHashes.contains(ghostUncle1.hash) is true
    val block0 = mine(blockFlow, blockTemplate0)
    checkBlock(block0, blockFlow).isRight is true

    val blockTemplate1 = blockTemplate0.setGhostUncles(blockFlow, AVector(ghostUncle1.hash))
    val block1         = mine(blockFlow, blockTemplate1)
    checkBlock(block1, blockFlow).isRight is true

    val ghostUncleHashes1 = sortGhostUncleHashes(AVector(ghostUncle0.hash, ghostUncle1.hash))
    val blockTemplate2    = blockTemplate0.setGhostUncles(blockFlow, ghostUncleHashes1)
    val block2            = mine(blockFlow, blockTemplate2)
    checkBlock(block2, blockFlow).leftValue isE DuplicateGhostUncleSinceDanube(ghostUncle0.hash)

    val ghostUncle2 = mineDuplicateGhostUncleBlock(blockFlow, ghostUncle1)
    BlockHeader.fromSameTemplate(ghostUncle1.header, ghostUncle2.header) is true
    val ghostUncleHashes2 = sortGhostUncleHashes(AVector(ghostUncle1.hash, ghostUncle2.hash))
    val blockTemplate3    = blockTemplate0.setGhostUncles(blockFlow, ghostUncleHashes2)
    val block3            = mine(blockFlow, blockTemplate3)
    checkBlock(block3, blockFlow).leftValue isE DuplicateGhostUncleSinceDanube(
      ghostUncleHashes2.last
    )

    val uncleHeight1      = ALPH.MaxGhostUncleAge
    val ghostUncle3       = mineDuplicateGhostUncleBlockAt(blockFlow, chainIndex, uncleHeight1)
    val ghostUncleHashes3 = sortGhostUncleHashes(AVector(ghostUncle1.hash, ghostUncle3.hash))
    val blockTemplate4    = blockTemplate0.setGhostUncles(blockFlow, ghostUncleHashes3)
    val block4            = mine(blockFlow, blockTemplate4)
    checkBlock(block4, blockFlow).leftValue isE DuplicateGhostUncleSinceDanube(ghostUncle3.hash)

    addAndCheck(blockFlow, block0, block1)
  }

  it should "invalidate block if a ghost uncle is a duplicate of used uncles" in new Fixture
    with GhostUncleFixture {
    setHardForkSince(HardFork.Danube)
    mineBlocks(blockFlow, chainIndex, ALPH.MaxGhostUncleAge)

    val uncleHeight = nextInt(2, ALPH.MaxGhostUncleAge - 1)
    val ghostUncle0 = mineValidGhostUncleBlockAt(blockFlow, chainIndex, uncleHeight)
    val miner       = getGenesisLockupScript(chainIndex.to)
    val template0   = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    template0.ghostUncleHashes is AVector(ghostUncle0.hash)
    val block0 = mine(blockFlow, template0)
    checkBlock(block0, blockFlow).isRight is true
    addAndCheck(blockFlow, block0)

    val ghostUncle1 = mineDuplicateGhostUncleBlock(blockFlow, ghostUncle0)
    BlockHeader.fromSameTemplate(ghostUncle0.header, ghostUncle1.header) is true
    val template1 = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    template1.ghostUncleHashes.isEmpty is true
    val template2 = template1.setGhostUncles(blockFlow, AVector(ghostUncle1.hash))
    val block1    = mine(blockFlow, template2)
    checkBlock(block1, blockFlow).leftValue isE DuplicateGhostUncleSinceDanube(ghostUncle1.hash)
  }

  it should "invalidate block with invalid uncle intra deps: since-rhone" in new Fixture {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))
    setHardForkSince(HardFork.Rhone)
    override lazy val chainIndex: ChainIndex = ChainIndex.unsafe(1, 2)
    val chain00                              = ChainIndex.unsafe(0, 0)
    val depBlock0                            = emptyBlock(blockFlow, chain00)
    val depBlock1                            = emptyBlock(blockFlow, chain00)
    addAndCheck(blockFlow, depBlock0, depBlock1)

    val miner              = getGenesisLockupScript(chainIndex.to)
    val uncleBlockTemplate = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    addAndCheck(blockFlow, emptyBlock(blockFlow, chainIndex))
    addAndCheck(blockFlow, emptyBlock(blockFlow, chainIndex))

    val blockTemplate = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    val chain00Hashes = blockFlow.getHashes(chain00, 1).rightValue
    val depHash       = if (chain00Hashes.head == depBlock0.hash) depBlock1.hash else depBlock0.hash
    val invalidUncle = mine(
      blockFlow,
      uncleBlockTemplate.copy(deps = uncleBlockTemplate.deps.replace(0, depHash))
    )
    addAndCheck(blockFlow, invalidUncle)
    val block =
      mine(
        blockFlow,
        blockTemplate.setGhostUncles(AVector(SelectedGhostUncle(invalidUncle.hash, miner, 1)))
      )
    checkBlock(block, blockFlow).leftValue is Right(InvalidGhostUncleDeps)
  }

  it should "validate block with valid uncles" in new SinceRhoneGhostUncleFixture {
    mineUncleBlocks(ALPH.MaxGhostUncleSize)
    val blockTemplate = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    blockTemplate.ghostUncleHashes.length is ALPH.MaxGhostUncleSize
    (0 until blockTemplate.ghostUncleHashes.length).foreach { size =>
      val selectedUncles = blockTemplate.ghostUncleHashes.take(size).map { hash =>
        getSelectedGhostUncle(blockTemplate.height, hash)
      }
      val block = mine(blockFlow, blockTemplate.setGhostUncles(selectedUncles))
      checkBlock(block, blockFlow).isRight is true
    }
  }

  it should "validate block if uncle header used by another uncle block" in new SinceRhoneGhostUncleFixture {
    val uncleHashes = mineUncleBlocks(3)
    val template0   = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    val block0      = mine(blockFlow, template0.setGhostUncles(blockFlow, AVector(uncleHashes(0))))
    val block1      = mine(blockFlow, template0.setGhostUncles(blockFlow, AVector(uncleHashes(1))))
    addAndCheck(blockFlow, block0, block1)
    val height = blockFlow.getMaxHeightByWeight(chainIndex).rightValue
    val hashes = blockFlow.getHashes(chainIndex, height).rightValue
    hashes.toSet is Set(block0.hash, block1.hash)

    val validUncleHash   = if (hashes.head == block0.hash) uncleHashes(1) else uncleHashes(0)
    val template1        = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    val ghostUncleHashes = sortGhostUncleHashes(AVector(validUncleHash, uncleHashes(2)))
    addAndCheck(blockFlow, mine(blockFlow, template1.setGhostUncles(blockFlow, ghostUncleHashes)))
  }

  it should "invalidate blocks with sequential txs for pre-rhone hardfork" in new Fixture {
    setHardForkBefore(HardFork.Rhone)
    override lazy val chainIndex = ChainIndex.unsafe(0, 0)

    val genesisKey              = genesisKeys(chainIndex.from.value)._1
    val (privateKey, publicKey) = chainIndex.from.generateKey
    val lockupScript            = LockupScript.p2pkh(publicKey)
    keyManager.addOne(lockupScript -> privateKey)

    val tx0 = transfer(blockFlow, genesisKey, publicKey, ALPH.alph(20)).nonCoinbase.head
    blockFlow.grandPool.add(chainIndex, tx0.toTemplate, TimeStamp.now())

    val tx1   = transferTx(blockFlow, chainIndex, lockupScript, ALPH.alph(10), None)
    val block = mineWithTxs(blockFlow, chainIndex, AVector(tx0, tx1))
    checkBlock(block, blockFlow).leftValue isE ExistInvalidTx(tx1, NonExistInput)
  }

  trait SequentialTxsFixture extends Fixture {
    setHardForkSince(HardFork.Rhone)
    val genesisKey = genesisKeys(chainIndex.from.value)._1
    val privateKey = {
      val (privateKey, publicKey) = chainIndex.from.generateKey
      addAndCheck(blockFlow, transfer(blockFlow, genesisKey, publicKey, ALPH.alph(20)))
      privateKey
    }

    def transferTx(
        from: PrivateKey,
        to: PublicKey,
        amount: U256,
        gasPrice: GasPrice
    ): Transaction = {
      transferWithGas(blockFlow, from, to, amount, gasPrice).nonCoinbase.head
    }
  }

  it should "validate sequential txs" in new SequentialTxsFixture {
    override lazy val chainIndex =
      chainIndexGenForBroker(brokerConfig).retryUntil(_.isIntraGroup).sample.get
    val (_, toPublicKey0) = chainIndex.to.generateKey
    val tx0 = transfer(blockFlow, privateKey, toPublicKey0, ALPH.alph(5)).nonCoinbase.head
    blockFlow.grandPool.add(chainIndex, tx0.toTemplate, TimeStamp.now())

    val (_, toPublicKey1) = chainIndex.to.generateKey
    val tx1 = transfer(blockFlow, privateKey, toPublicKey1, ALPH.alph(5)).nonCoinbase.head
    tx1.unsigned.inputs.head.outputRef is tx0.fixedOutputRefs(1)

    val block = mineWithTxs(blockFlow, chainIndex, AVector(tx0, tx1))
    block.pass()(checkBlockUnit(_, blockFlow))
  }

  it should "invalidate sequential txs if the input does not exist" in new SequentialTxsFixture {
    override lazy val chainIndex =
      chainIndexGenForBroker(brokerConfig).retryUntil(_.isIntraGroup).sample.get
    val (_, toPublicKey0) = chainIndex.to.generateKey
    val now               = TimeStamp.now()
    val tx0 = transfer(blockFlow, privateKey, toPublicKey0, ALPH.oneAlph).nonCoinbase.head
    blockFlow.grandPool.add(chainIndex, tx0.toTemplate, now)
    val tx1 = transfer(blockFlow, privateKey, toPublicKey0, ALPH.oneAlph).nonCoinbase.head
    tx1.unsigned.inputs.forall(input =>
      tx0.unsigned.fixedOutputRefs.contains(input.outputRef)
    ) is true
    blockFlow.grandPool.add(chainIndex, tx1.toTemplate, now.plusMillisUnsafe(1))
    val tx2 = transfer(blockFlow, privateKey, toPublicKey0, ALPH.oneAlph).nonCoinbase.head
    tx2.unsigned.inputs.forall(input =>
      tx1.unsigned.fixedOutputRefs.contains(input.outputRef)
    ) is true
    blockFlow.grandPool.add(chainIndex, tx2.toTemplate, now.plusMillisUnsafe(2))

    val block0 = mineWithTxs(blockFlow, chainIndex, AVector(tx0, tx2))
    checkBlock(block0, blockFlow).leftValue isE ExistInvalidTx(tx2, NonExistInput)

    val block1 = mineWithTxs(blockFlow, chainIndex, AVector(tx0, tx1, tx2))
    block1.pass()(checkBlockUnit(_, blockFlow))
  }

  it should "check double spending for sequential txs" in new SequentialTxsFixture {
    override lazy val chainIndex =
      chainIndexGenForBroker(brokerConfig).retryUntil(_.isIntraGroup).sample.get
    val (_, toPublicKey0) = chainIndex.to.generateKey
    val tx0 = transfer(blockFlow, privateKey, toPublicKey0, ALPH.alph(5)).nonCoinbase.head
    blockFlow.grandPool.add(chainIndex, tx0.toTemplate, TimeStamp.now())

    val (_, toPublicKey1) = chainIndex.to.generateKey
    val (_, toPublicKey2) = chainIndex.to.generateKey
    val tx1 = transfer(blockFlow, privateKey, toPublicKey1, ALPH.alph(5)).nonCoinbase.head
    val tx2 = transfer(blockFlow, privateKey, toPublicKey2, ALPH.alph(5)).nonCoinbase.head
    tx1.unsigned.inputs.length is 1
    tx1.unsigned.inputs.head.outputRef is tx0.fixedOutputRefs(1)
    tx1.unsigned.inputs is tx2.unsigned.inputs
    tx2.unsigned.inputs.head.outputRef is tx0.fixedOutputRefs(1)

    val block = mineWithTxs(blockFlow, chainIndex, AVector(tx0, tx1, tx2))
    block.fail(BlockDoubleSpending)(checkBlockDoubleSpending)
  }

  it should "invalidate inter group blocks with sequential txs" in new SequentialTxsFixture {
    override lazy val chainIndex =
      chainIndexGenForBroker(brokerConfig).retryUntil(!_.isIntraGroup).sample.get

    val (_, toPublicKey0) = chainIndex.to.generateKey
    val tx0 = transfer(blockFlow, privateKey, toPublicKey0, ALPH.alph(5)).nonCoinbase.head
    blockFlow.grandPool.add(chainIndex, tx0.toTemplate, TimeStamp.now())

    val (_, toPublicKey1) = chainIndex.to.generateKey
    val tx1   = transfer(blockFlow, privateKey, toPublicKey1, ALPH.alph(5)).nonCoinbase.head
    val block = mineWithTxs(blockFlow, chainIndex, AVector(tx0, tx1))
    checkBlock(block, blockFlow).leftValue isE ExistInvalidTx(tx1, NonExistInput)
  }

  trait PoLWCoinbaseFixture extends SinceRhoneGhostUncleFixture {
    lazy val (minerPrivateKey, minerPublicKey) = chainIndex.to.generateKey
    lazy val minerLockupScript                 = LockupScript.p2pkh(minerPublicKey)
    lazy val (genesisPrivateKey, _, _)         = genesisKeys(chainIndex.from.value)

    private def prepareUtxos() = {
      val (privateKey, publicKey) = chainIndex.from.generateKey
      val utxoLength              = 2
      (0 until utxoLength).foreach { _ =>
        val block = transfer(blockFlow, genesisPrivateKey, publicKey, ALPH.alph(1))
        addAndCheck(blockFlow, block)
      }
      val lockupScript = LockupScript.p2pkh(publicKey)
      val utxos        = blockFlow.getUsableUtxos(None, lockupScript, Int.MaxValue).rightValue
      utxos.length is utxoLength
      (utxos, privateKey)
    }

    def buildPoLWCoinbaseTx(
        header: BlockHeader,
        uncles: AVector[SelectedGhostUncle],
        utxos: AVector[FlowUtils.AssetOutputInfo],
        privateKey: PrivateKey
    ): Transaction = {
      val emission = consensusConfigs.getConsensusConfig(header.timestamp).emission
      val reward   = emission.reward(header).asInstanceOf[Emission.PoLW]
      val rewardOutputs = Coinbase.calcPoLWCoinbaseRewardOutputs(
        chainIndex,
        minerLockupScript,
        uncles,
        reward,
        U256.Zero,
        header.timestamp,
        ByteString.empty
      )
      val publicKey    = privateKey.publicKey
      val lockupScript = LockupScript.p2pkh(publicKey)
      val unlockScript = UnlockScript.polw(publicKey)
      val gas = GasEstimation.estimateWithSameP2PKHInputs(utxos.length, rewardOutputs.length + 1)
      val unsignedTx =
        blockFlow
          .polwCoinbase(lockupScript, unlockScript, rewardOutputs, reward.burntAmount, utxos, gas)
          .rightValue
      val preImage  = UnlockScript.PoLW.buildPreImage(lockupScript, minerLockupScript)
      val signature = Byte64.from(SignatureSchema.sign(preImage, privateKey))
      Transaction.from(unsignedTx, AVector(signature))
    }

    private def randomPoLWTarget(blockTargetTime: Duration): Target = {
      val from = Target.from(HashRate.unsafe(BigInteger.ONE.shiftLeft(62)), blockTargetTime)
      val to   = Target.from(HashRate.unsafe(BigInteger.ONE.shiftLeft(61)), blockTargetTime)
      Target.unsafe(nextU256(U256.unsafe(from.value), U256.unsafe(to.value)).v)
    }

    def emptyPoLWBlock(uncleSize: Int): Block = {
      val (utxos, privateKey) = prepareUtxos()
      val uncleHashes         = mineUncleBlocks(uncleSize)
      val block               = mineBlockTemplate(blockFlow, chainIndex)
      block.ghostUncleHashes.rightValue.toSet is uncleHashes.toSet
      val consensusConfig = consensusConfigs.getConsensusConfig(block.timestamp)
      val polwTarget      = randomPoLWTarget(consensusConfig.blockTargetTime)
      assume(consensusConfig.emission.shouldEnablePoLW(polwTarget))
      val header      = block.header.copy(target = polwTarget)
      val blockHeight = blockFlow.getMaxHeightByWeight(chainIndex).rightValue + 1
      val uncles = block.ghostUncleHashes.rightValue.take(uncleSize).map { hash =>
        val uncleMiner  = blockFlow.getBlockUnsafe(hash).minerLockupScript
        val uncleHeight = blockFlow.getHeightUnsafe(hash)
        SelectedGhostUncle(hash, uncleMiner, blockHeight - uncleHeight)
      }
      uncles.length is uncleSize
      val coinbaseTx = buildPoLWCoinbaseTx(header, uncles, utxos, privateKey)
      block.copy(header = header, transactions = AVector(coinbaseTx))
    }

    def emptyPoLWBlock(): Block = {
      emptyPoLWBlock(nextInt(0, ALPH.MaxGhostUncleSize))
    }
  }

  it should "check PoLW coinbase tx since rhone" in new PoLWCoinbaseFixture {
    setHardForkSince(HardFork.Rhone)
    implicit val validator: (Block) => BlockValidationResult[Unit] = (block: Block) => {
      val hardFork  = networkConfig.getHardFork(block.timestamp)
      val groupView = blockFlow.getMutableGroupView(chainIndex.from).rightValue
      checkCoinbase(blockFlow, chainIndex, block, groupView, hardFork)
    }

    val block = emptyPoLWBlock()
    block.pass()

    info("invalid input unlock script")
    val invalidUnlockScript = p2pkhUnlockGen(chainIndex.from).sample.get
    (block.coinbase.unsigned.inputs.length > 1) is true
    block.Coinbase
      .input(_.copy(unlockScript = invalidUnlockScript), 0)
      .fail(InvalidPoLWInputUnlockScript)
    block.Coinbase
      .input(_.copy(unlockScript = invalidUnlockScript), 1)
      .fail(PoLWUnlockScriptNotTheSame)

    info("invalid output lockup script")
    val invalidLockupScript = assetLockupGen(chainIndex.from).sample.get
    val changeOutputIndex   = block.coinbase.unsigned.fixedOutputs.length - 1
    block.Coinbase
      .output(_.copy(lockupScript = invalidLockupScript), changeOutputIndex)
      .fail(InvalidPoLWChangeOutputLockupScript)

    info("invalid block reward")
    block.Coinbase
      .output(o => o.copy(amount = o.amount.subUnsafe(U256.One)))
      .fail(InvalidCoinbaseReward)
    block.Coinbase
      .output(o => o.copy(lockTime = o.lockTime.plusSecondsUnsafe(1)))
      .fail(InvalidCoinbaseLockupPeriod)
    block.Coinbase
      .output(o => o.copy(amount = o.amount.subUnsafe(U256.One)))
      .Coinbase
      .output(o => o.copy(amount = o.amount.addUnsafe(U256.One)), 1)
      .fail(InvalidCoinbaseLockedAmount)

    info("invalid uncle reward")
    val block1 = emptyPoLWBlock(1)
    block1.pass()
    block1.Coinbase
      .output(o => o.copy(amount = o.amount.subUnsafe(U256.One)), 1)
      .fail(InvalidCoinbaseReward)
    block1.Coinbase
      .output(o => o.copy(lockupScript = invalidLockupScript), 1)
      .fail(InvalidGhostUncleMiner)
    block1.Coinbase
      .output(o => o.copy(lockTime = o.lockTime.plusSecondsUnsafe(1)), 1)
      .fail(InvalidCoinbaseLockupPeriod)
    block1.Coinbase
      .output(o => o.copy(amount = o.amount.subUnsafe(U256.One)), 1)
      .Coinbase
      .output(o => o.copy(amount = o.amount.addUnsafe(U256.One)), 2)
      .fail(InvalidCoinbaseLockedAmount)

    val block2 = emptyPoLWBlock(2)
    block2.pass()
    block2.Coinbase
      .output(o => o.copy(amount = o.amount.subUnsafe(U256.One)), 1)
      .fail(InvalidCoinbaseReward)
    block2.Coinbase
      .output(o => o.copy(lockupScript = invalidLockupScript), 1)
      .fail(InvalidGhostUncleMiner)
    block2.Coinbase
      .output(o => o.copy(lockTime = o.lockTime.plusSecondsUnsafe(1)), 1)
      .fail(InvalidCoinbaseLockupPeriod)
    block2.Coinbase
      .output(o => o.copy(amount = o.amount.subUnsafe(U256.One)), 1)
      .Coinbase
      .output(o => o.copy(amount = o.amount.addUnsafe(U256.One)), 2)
      .fail(InvalidCoinbaseLockedAmount)

    block2.Coinbase
      .output(o => o.copy(amount = o.amount.subUnsafe(U256.One)), 2)
      .fail(InvalidCoinbaseReward)
    block2.Coinbase
      .output(o => o.copy(lockTime = o.lockTime.plusSecondsUnsafe(1)), 2)
      .fail(InvalidCoinbaseLockupPeriod)
    block2.Coinbase
      .output(o => o.copy(lockupScript = invalidLockupScript), 2)
      .fail(InvalidGhostUncleMiner)
    block2.Coinbase
      .output(o => o.copy(amount = o.amount.subUnsafe(U256.One)), 2)
      .Coinbase
      .output(o => o.copy(amount = o.amount.addUnsafe(U256.One)), 3)
      .fail(InvalidCoinbaseLockedAmount)

    block2.Coinbase
      .output(o => o.copy(amount = o.amount.subUnsafe(U256.One)), 3)
      .fail(InvalidCoinbaseReward)

    block2.Coinbase.gasAmount(_.subUnsafe(1)).fail(InvalidCoinbaseReward)
    block2.Coinbase.gasAmount(_.addUnsafe(1)).fail(InvalidCoinbaseReward)
  }

  it should "check PoLW coinbase tx pre-rhone" in new PoLWCoinbaseFixture {
    setHardForkBefore(HardFork.Rhone)
    implicit val validator: (Block) => BlockValidationResult[Unit] = (block: Block) => {
      val hardFork = networkConfig.getHardFork(block.timestamp)
      val groupView =
        blockFlow.getMutableGroupViewPreDanube(chainIndex.from, block.blockDeps).rightValue
      checkCoinbase(blockFlow, chainIndex, block, groupView, hardFork)
    }

    val block = emptyPoLWBlock(0)
    block.fail(InvalidPoLWBeforeRhoneHardFork)
  }

  trait TestnetFixture extends Fixture {
    override lazy val chainIndex = ChainIndex.unsafe(0, 0)
    lazy val whitelistedMiner =
      ALPH.testnetWhitelistedMiners
        .filter(_.groupIndex == chainIndex.to)
        .head
        .asInstanceOf[LockupScript.Asset]
    lazy val randomMiner = preDanubeLockupGen(chainIndex.from).sample.get

    def newBlock(miner: LockupScript.Asset): Block = {
      val template   = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
      val emptyBlock = Block(template.dummyHeader(), template.transactions)
      emptyBlock.Coinbase.output(o => o.copy(lockupScript = miner))
    }

    implicit lazy val validator: (Block) => BlockValidationResult[Unit] = (block: Block) => {
      val hardFork = networkConfig.getHardFork(block.timestamp)
      checkTestnetMiner(block, hardFork)
    }
  }

  it should "check miner for tesnet: since-rhone" in new TestnetFixture {
    override val configValues: Map[String, Any] = Map(("alephium.network.network-id", 1))
    setHardForkSince(HardFork.Rhone)
    networkConfig.networkId is NetworkId.AlephiumTestNet

    newBlock(whitelistedMiner).pass()
    newBlock(randomMiner).fail(InvalidTestnetMiner)
  }

  it should "not check miner for testnet pre-Rhone" in new TestnetFixture {
    override val configValues: Map[String, Any] = Map(("alephium.network.network-id", 1))
    setHardForkBefore(HardFork.Rhone)
    networkConfig.networkId is NetworkId.AlephiumTestNet

    newBlock(whitelistedMiner).pass()
    newBlock(randomMiner).pass()
  }

  it should "not check miner for devnet" in new TestnetFixture {
    setHardForkBefore(HardFork.Rhone)
    networkConfig.networkId is NetworkId.AlephiumDevNet

    newBlock(whitelistedMiner).pass()
    newBlock(randomMiner).pass()
  }

  trait ConflictedTxsFixture extends Fixture {
    lazy val fromGroup = brokerConfig.randomGroupIndex()
    lazy val toGroup0  = GroupIndex.unsafe((fromGroup.value + 1) % brokerConfig.groups)
    lazy val toGroup1  = GroupIndex.unsafe((toGroup0.value + 1) % brokerConfig.groups)
    lazy val block0    = transfer(blockFlow, ChainIndex(fromGroup, toGroup0))
    lazy val block1 = {
      val block = transfer(blockFlow, ChainIndex(fromGroup, toGroup1))
      addAndCheck(blockFlow, block0)
      mineWithTxs(blockFlow, ChainIndex(fromGroup, toGroup1), block.nonCoinbase)
    }
  }

  it should "disallow conflicted txs before danube" in new ConflictedTxsFixture {
    setHardForkBefore(HardFork.Danube)
    validate(block0, blockFlow).isRight is true
    blockFlow.getCache(block0).blockCache.contains(block0.hash) is true
    validate(block1, blockFlow).leftValue is Right(InvalidFlowTxs)
  }

  it should "allow conflicted txs since danube" in new ConflictedTxsFixture {
    setHardForkSince(HardFork.Danube)
    validate(block0, blockFlow).isRight is true
    blockFlow.getCache(block0).blockCache.contains(block0.hash) is false
    blockFlow.getCache(block0).add(block0)
    validate(block1, blockFlow).isRight is true
  }
}
