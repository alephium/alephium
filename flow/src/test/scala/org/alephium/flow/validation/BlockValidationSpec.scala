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

import org.alephium.flow.FlowFixture
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
    override val configValues = Map(
      ("alephium.network.leman-hard-fork-timestamp", TimeStamp.now().plusHoursUnsafe(1).millis),
      ("alephium.network.rhone-hard-fork-timestamp", TimeStamp.Max.millis)
    )
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Mainnet
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

  trait CoinbaseFormatFixture extends Fixture {
    val output0         = assetOutputGen.sample.get
    val emptyOutputs    = AVector.empty[AssetOutput]
    val emptySignatures = AVector.empty[Signature]
    val script          = StatefulScript.alwaysFail
    val testSignatures  = AVector(Signature.generate)
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
    implicit val validator                 = (blk: Block) => checkCoinbaseEasy(blk, 0, false)
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
    implicit val validator = (blk: Block) => checkCoinbaseEasy(blk, 0, isPoLW = true)
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
    override val configValues = Map(
      ("alephium.network.rhone-hard-fork-timestamp", TimeStamp.Max.millis)
    )
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
  }

  it should "check coinbase data for pre-rhone hardfork" in new CoinbaseDataFixture {
    override val configValues =
      Map(("alephium.network.rhone-hard-fork-timestamp", TimeStamp.Max.millis))
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Leman

    implicit val validator = (blk: Block) => checkCoinbaseData(blk.chainIndex, blk).map(_ => ())

    val block          = emptyBlock(blockFlow, chainIndex)
    val selectedUncles = AVector.empty
    block.coinbase.unsigned.fixedOutputs.head.additionalData is serialize(coinbaseData)
    block.pass()

    info("wrong block timestamp")
    val data0 = wrongTimeStamp(block.timestamp.plusSecondsUnsafe(5))
    block.Coinbase.output(_.copy(additionalData = data0)).fail(InvalidCoinbaseData)

    info("wrong chain index")
    val data1 = wrongChainIndex(chainIndexGen.retryUntil(_ != chainIndex).sample.get)
    block.Coinbase.output(_.copy(additionalData = data1)).fail(InvalidCoinbaseData)

    info("wrong format")
    val wrongFormat = ByteString("wrong-coinbase-data-format")
    block.Coinbase.output(_.copy(additionalData = wrongFormat)).fail(InvalidCoinbaseData)

    info("empty fixed outputs")
    block.Coinbase
      .unsignedTx(unsigned => unsigned.copy(fixedOutputs = AVector.empty))
      .fail(InvalidCoinbaseFormat)
  }

  it should "check uncles for pre-rhone hardfork" in new Fixture {
    override val configValues =
      Map(("alephium.network.rhone-hard-fork-timestamp", TimeStamp.Max.millis))
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Leman

    val ghostUncles = AVector.fill(ALPH.MaxGhostUncleSize)(
      GhostUncleData(BlockHash.random, assetLockupGen(chainIndex.to).sample.get)
    )

    implicit val validator = (blk: Block) => {
      networkConfig.getHardFork(blk.timestamp) is HardFork.Leman
      checkGhostUncles(blockFlow, blk.chainIndex, blk, ghostUncles).map(_ => ())
    }

    val block = emptyBlock(blockFlow, chainIndex)
    block.fail(InvalidGhostUnclesBeforeRhoneHardFork)
  }

  it should "check coinbase data for rhone hardfork" in new CoinbaseDataFixture {
    override val configValues = Map(("alephium.network.rhone-hard-fork-timestamp", 0))
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Rhone

    implicit val validator = (blk: Block) => checkCoinbaseData(blk.chainIndex, blk).map(_ => ())

    val uncleBlock     = blockGen(chainIndex).sample.get
    val uncleMiner     = assetLockupGen(chainIndex.to).sample.get
    val selectedUncles = AVector(SelectedGhostUncle(uncleBlock.hash, uncleMiner, 1))
    val block = mineWithoutCoinbase(
      blockFlow,
      chainIndex,
      AVector.empty,
      TimeStamp.now(),
      selectedUncles
    )
    block.coinbase.unsigned.fixedOutputs.head.additionalData is serialize(coinbaseData)
    block.pass()

    info("empty uncles")
    val block1 = emptyBlock(blockFlow, chainIndex)
    block1.ghostUncleHashes.rightValue.isEmpty is true
    block1.pass()

    info("wrong block timestamp")
    val data0 = wrongTimeStamp(block.timestamp.plusSecondsUnsafe(5))
    block.Coinbase.output(_.copy(additionalData = data0)).fail(InvalidCoinbaseData)

    info("wrong chain index")
    val data1 = wrongChainIndex(chainIndexGen.retryUntil(_ != chainIndex).sample.get)
    block.Coinbase.output(_.copy(additionalData = data1)).fail(InvalidCoinbaseData)

    info("wrong format")
    val wrongFormat = ByteString("wrong-coinbase-data-format")
    block.Coinbase.output(_.copy(additionalData = wrongFormat)).fail(InvalidCoinbaseData)

    info("empty fixed outputs")
    block.Coinbase
      .unsignedTx(unsigned => unsigned.copy(fixedOutputs = AVector.empty))
      .fail(InvalidCoinbaseFormat)
  }

  it should "check coinbase locked amount pre-rhone" in new Fixture {
    override val configValues =
      Map(("alephium.network.rhone-hard-fork-timestamp", TimeStamp.Max.millis))
    val block              = emptyBlock(blockFlow, chainIndex)
    val consensusConfig    = consensusConfigs.getConsensusConfig(block.timestamp)
    val miningReward       = consensusConfig.emission.reward(block.header).miningReward
    val lockedAmount       = miningReward
    implicit val validator = (blk: Block) => checkLockedReward(blk, AVector(lockedAmount))

    info("valid")
    block.pass()

    info("invalid locked amount")
    block.Coinbase.output(_.copy(amount = U256.One)).fail(InvalidCoinbaseLockedAmount)

    info("invalid lockup period")
    block.Coinbase.output(_.copy(lockTime = TimeStamp.now())).fail(InvalidCoinbaseLockupPeriod)
  }

  it should "check coinbase reward pre-rhone" in new Fixture {
    override val configValues =
      Map(("alephium.network.rhone-hard-fork-timestamp", TimeStamp.Max.millis))
    val block = emptyBlock(blockFlow, chainIndex)
    implicit val validator = (blk: Block) => {
      val groupView = blockFlow.getMutableGroupView(blk).rightValue
      checkCoinbase(blockFlow, blk.chainIndex, blk, groupView, HardFork.Leman)
    }

    val consensusConfig = consensusConfigs.getConsensusConfig(block.timestamp)
    val miningReward    = consensusConfig.emission.reward(block.header).miningReward
    block.Coinbase.output(_.copy(amount = miningReward)).pass()

    val invalidMiningReward = miningReward.subUnsafe(1)
    block.Coinbase.output(_.copy(amount = invalidMiningReward)).fail(InvalidCoinbaseReward)
  }

  it should "check gas reward cap for Genesis fork" in new GenesisForkFixture {

    implicit val validator = (blk: Block) => {
      val groupView = blockFlow.getMutableGroupView(blk).rightValue
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
    override val configValues =
      Map(("alephium.network.rhone-hard-fork-timestamp", TimeStamp.Max.millis))
    networkConfig.getHardFork(TimeStamp.now()).isLemanEnabled() is true

    implicit val validator = (blk: Block) => {
      val groupView = blockFlow.getMutableGroupView(blk).rightValue
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
    implicit val validator = checkBlockDoubleSpending _
    val intraChainIndex    = ChainIndex.unsafe(0, 0)

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
    forAll(chainIndexGenForBroker(brokerConfig)) { index =>
      val block0 = transfer(blockFlow, index)
      addAndCheck(blockFlow, block0)

      for (to <- 0 until brokerConfig.groups) {
        // UTXOs spent by `block0.nonCoinbase` can not be spent again
        val from = block0.chainIndex.from.value
        val block =
          mineWithTxs(blockFlow, ChainIndex.unsafe(from, to))((_, _) => block0.nonCoinbase)
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

  it should "validate old blocks" in new GenesisForkFixture {
    val block0     = transfer(blockFlow, chainIndex)
    val newBlockTs = ALPH.LaunchTimestamp.plusSecondsUnsafe(10)
    val block1     = mineWithoutCoinbase(blockFlow, chainIndex, block0.nonCoinbase, newBlockTs)
    checkBlockUnit(block1, blockFlow) isE ()
  }

  it should "invalidate blocks with breaking instrs" in new Fixture {
    override val configValues =
      Map(("alephium.network.rhone-hard-fork-timestamp", TimeStamp.Max.millis))
    override lazy val chainIndex: ChainIndex = ChainIndex.unsafe(0, 0)
    val newStorages =
      StoragesFixture.buildStorages(rootPath.resolveSibling(Hash.generate.toHexString))
    val genesisNetworkConfig = new NetworkConfigFixture.Default {
      override def lemanHardForkTimestamp: TimeStamp = TimeStamp.now().plusHoursUnsafe(1)
      override def rhoneHardForkTimestamp: TimeStamp = TimeStamp.Max
    }.networkConfig
    val lemanNetworkConfig = new NetworkConfigFixture.Default {
      override def rhoneHardForkTimestamp: TimeStamp = TimeStamp.Max
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
        AVector(Method(true, false, false, false, 0, 0, 0, AVector(vm.TxGasFee)))
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

  trait RhoneCoinbaseFixture extends Fixture {
    def randomLockupScript: LockupScript.Asset = {
      val (_, toPublicKey) = chainIndex.to.generateKey
      LockupScript.p2pkh(toPublicKey)
    }

    def getMiningReward(block: Block): U256 = {
      val consensusConfig = consensusConfigs.getConsensusConfig(block.timestamp)
      consensusConfig.emission.reward(block.header).miningReward
    }

    def mineBlock(parentHash: BlockHash): Block = {
      val miner       = randomLockupScript
      val template0   = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
      val parentIndex = brokerConfig.groups - 1 + chainIndex.to.value
      val newDeps     = template0.deps.replace(parentIndex, parentHash)
      val template1 = blockFlow
        .rebuild(template0, template0.transactions.init, AVector.empty, miner)
        .copy(
          deps = newDeps,
          depStateHash =
            blockFlow.getDepStateHash(BlockDeps.unsafe(newDeps), chainIndex.from).rightValue
        )
      mine(blockFlow, template1)
    }

    def mineBlockWith1Uncle(heightDiff: Int): Block = {
      (0 until heightDiff).foreach(_ => addAndCheck(blockFlow, emptyBlock(blockFlow, chainIndex)))
      val maxHeight  = blockFlow.getMaxHeightByWeight(chainIndex).rightValue
      val parentHash = blockFlow.getHashes(chainIndex, maxHeight - heightDiff).rightValue.head
      addAndCheck(blockFlow, mineBlock(parentHash))
      val block            = mineBlockTemplate(blockFlow, chainIndex)
      val ghostUncleHashes = block.ghostUncleHashes.rightValue
      ghostUncleHashes.length is 1
      val blockHeight = maxHeight + 1
      (blockHeight - blockFlow.getHeight(ghostUncleHashes.head).rightValue) is heightDiff
      block
    }

    def mineBlockWith2Uncle(heightDiff0: Int, heightDiff1: Int): (Block, Int, Int) = {
      assume(heightDiff0 <= heightDiff1)
      (0 until ALPH.MaxGhostUncleAge).foreach(_ =>
        addAndCheck(blockFlow, emptyBlock(blockFlow, chainIndex))
      )
      val maxHeight   = blockFlow.getMaxHeightByWeight(chainIndex).rightValue
      val parentHash0 = blockFlow.getHashes(chainIndex, maxHeight - heightDiff0).rightValue.head
      addAndCheck(blockFlow, mineBlock(parentHash0))
      val parentHash1 = blockFlow.getHashes(chainIndex, maxHeight - heightDiff1).rightValue.head
      addAndCheck(blockFlow, mineBlock(parentHash1))

      val block            = mineBlockTemplate(blockFlow, chainIndex)
      val ghostUncleHashes = block.ghostUncleHashes.rightValue
      ghostUncleHashes.length is 2
      val blockHeight = maxHeight + 1
      val diffs = ghostUncleHashes.map(hash => blockHeight - blockFlow.getHeight(hash).rightValue)
      ((diffs == AVector(heightDiff0, heightDiff1)) ||
        (diffs == AVector(heightDiff1, heightDiff0))) is true
      (block, diffs(0), diffs(1))
    }
  }

  it should "check coinbase locked amount rhone" in new RhoneCoinbaseFixture {
    {
      info("block has no uncle")
      val block              = emptyBlock(blockFlow, chainIndex)
      val miningReward       = getMiningReward(block)
      val mainChainReward    = Coinbase.calcMainChainReward(miningReward)
      val lockedReward       = mainChainReward
      implicit val validator = (blk: Block) => checkLockedReward(blk, AVector(lockedReward))

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
      val heightDiff      = Random.between(1, ALPH.MaxGhostUncleAge)
      val block           = mineBlockWith1Uncle(heightDiff)
      val miningReward    = getMiningReward(block)
      val mainChainReward = Coinbase.calcMainChainReward(miningReward)
      val uncleReward     = Coinbase.calcGhostUncleReward(mainChainReward, heightDiff)
      val blockReward     = mainChainReward.addUnsafe(uncleReward.divUnsafe(32))
      implicit val validator =
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
      val diffs = (0 until 2).map(_ => Random.between(1, ALPH.MaxGhostUncleAge)).sorted
      val (block, diff0, diff1) = mineBlockWith2Uncle(diffs(0), diffs(1))
      val miningReward          = getMiningReward(block)
      val mainChainReward       = Coinbase.calcMainChainReward(miningReward)
      val uncleReward0          = Coinbase.calcGhostUncleReward(mainChainReward, diff0)
      val uncleReward1          = Coinbase.calcGhostUncleReward(mainChainReward, diff1)
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

  it should "check coinbase reward rhone" in new RhoneCoinbaseFixture {
    implicit val validator = (blk: Block) => {
      val groupView = blockFlow.getMutableGroupView(blk).rightValue
      checkCoinbase(blockFlow, blk.chainIndex, blk, groupView, HardFork.Rhone)
    }

    {
      info("block has no uncle")
      val block = emptyBlock(blockFlow, chainIndex)

      info("valid")
      block.pass()

      val miningReward    = getMiningReward(block)
      val mainChainReward = Coinbase.calcMainChainReward(miningReward)

      info("invalid block reward")
      block.Coinbase.output(_.copy(amount = miningReward)).fail(InvalidCoinbaseReward)
      block.Coinbase
        .output(_.copy(amount = mainChainReward.subOneUnsafe()))
        .fail(InvalidCoinbaseReward)
    }

    {
      info("block has 1 uncle")
      val heightDiff      = Random.between(1, ALPH.MaxGhostUncleAge)
      val block           = mineBlockWith1Uncle(heightDiff)
      val miningReward    = getMiningReward(block)
      val mainChainReward = Coinbase.calcMainChainReward(miningReward)
      val uncleReward     = Coinbase.calcGhostUncleReward(mainChainReward, heightDiff)

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
      val diffs = (0 until 2).map(_ => Random.between(1, ALPH.MaxGhostUncleAge)).sorted
      val (block, diff0, diff1) = mineBlockWith2Uncle(diffs(0), diffs(1))
      val miningReward          = getMiningReward(block)
      val mainChainReward       = Coinbase.calcMainChainReward(miningReward)
      val uncleReward0          = Coinbase.calcGhostUncleReward(mainChainReward, diff0)
      val uncleReward1          = Coinbase.calcGhostUncleReward(mainChainReward, diff1)

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

  trait RhoneFixture extends Fixture {
    override val configValues = Map(
      ("alephium.network.rhone-hard-fork-timestamp", TimeStamp.now().millis)
    )
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Rhone
    lazy val miner = getGenesisLockupScript(chainIndex.to)

    def mineBlocks(size: Int): AVector[BlockHash] = {
      val blocks = (0 to size).map(_ => emptyBlockWithMiner(blockFlow, chainIndex, miner))
      addAndCheck(blockFlow, blocks: _*)
      val hashes = blockFlow.getHashes(chainIndex, 1).rightValue
      hashes.length is size + 1
      hashes
    }

    def mineChain(size: Int): AVector[Block] = {
      val blockFlow = isolatedBlockFlow()
      val blocks = (0 until size).map(_ => {
        val block = emptyBlockWithMiner(blockFlow, chainIndex, miner)
        addAndCheck(blockFlow, block)
        block
      })
      AVector.from(blocks)
    }
  }

  it should "invalidate block with invalid uncles size" in new RhoneFixture {
    val hashes        = mineBlocks(ALPH.MaxGhostUncleSize + 1)
    val blockTemplate = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    blockTemplate.ghostUncleHashes.length is ALPH.MaxGhostUncleSize
    val ghostUncleHashes = hashes.tail
    ghostUncleHashes.length is ALPH.MaxGhostUncleSize + 1
    val block = mine(
      blockFlow,
      blockTemplate.setGhostUncles(
        ghostUncleHashes.map(hash => SelectedGhostUncle(hash, miner, 1))
      )
    )
    checkBlock(block, blockFlow).left.value isE InvalidGhostUncleSize
  }

  it should "invalidate block with unsorted uncles" in new RhoneFixture {
    mineBlocks(ALPH.MaxGhostUncleSize)
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

    val uncleBlock      = blockFlow.getBlockUnsafe(blockTemplate.ghostUncleHashes.head)
    val uncleBlockMiner = uncleBlock.coinbase.unsigned.fixedOutputs.head.lockupScript
    val block1 =
      mine(
        blockFlow,
        rebuildTemplate(
          AVector(SelectedGhostUncle(blockTemplate.ghostUncleHashes.head, uncleBlockMiner, 1))
        )
      )
    checkBlock(block1, blockFlow).isRight is true

    val block2 =
      mine(
        blockFlow,
        rebuildTemplate(
          AVector.fill(2)(SelectedGhostUncle(blockTemplate.ghostUncleHashes.head, miner, 1))
        )
      )
    checkBlock(block2, blockFlow).left.value isE UnsortedGhostUncles

    val block3 =
      mine(
        blockFlow,
        rebuildTemplate(
          blockTemplate.ghostUncleHashes.reverse.map(hash => SelectedGhostUncle(hash, miner, 1))
        )
      )
    checkBlock(block3, blockFlow).left.value isE UnsortedGhostUncles
  }

  it should "invalidate block if the uncle miner is invalid" in new RhoneFixture {
    mineBlocks(ALPH.MaxGhostUncleSize)
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

  it should "invalidate block with used uncles" in new RhoneFixture {
    mineBlocks(ALPH.MaxGhostUncleSize)
    val block0 = mineBlockTemplate(blockFlow, chainIndex)
    addAndCheck(blockFlow, block0)

    val block1Template = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    block1Template.ghostUncleHashes.isEmpty is true
    val block1 = mine(
      blockFlow,
      block1Template.setGhostUncles(
        AVector(SelectedGhostUncle(block0.ghostUncleHashes.rightValue.head, miner, 1))
      )
    )
    checkBlock(block1, blockFlow).left.value isE GhostUnclesAlreadyUsed
  }

  it should "invalidate block if uncle is sibling" in new RhoneFixture {
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
    checkBlock(block11, blockFlow).left.value isE GhostUncleDoesNotExist

    addAndCheck(blockFlow, block10)
    checkBlock(block11, blockFlow).left.value isE GhostUncleHashConflictWithParentHash
  }

  it should "invalidate block if uncle is ancestor" in new RhoneFixture {
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

  it should "invalidate block if uncles' parent is not from mainchain" in new RhoneFixture {
    val (blocks0, blocks1) = (mineChain(4), mineChain(4))
    addAndCheck(blockFlow, blocks0.toSeq: _*)
    addAndCheck(blockFlow, blocks1.toSeq: _*)
    val hashes = blockFlow.getHashes(chainIndex, 3).rightValue
    hashes.length is 2

    val blockTemplate = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    blockTemplate.ghostUncleHashes.length is 1
    val ghostUncleHashes = blockTemplate.ghostUncleHashes ++ hashes.tail
    val block = mine(
      blockFlow,
      blockTemplate.setGhostUncles(ghostUncleHashes.map(hash => SelectedGhostUncle(hash, miner, 1)))
    )
    checkBlock(block, blockFlow).left.value isE GhostUncleHashConflictWithParentHash
  }

  it should "invalidate block with invalid uncle intra deps" in new Fixture {
    override val configValues = Map(
      ("alephium.broker.broker-num", 1),
      ("alephium.network.rhone-hard-fork-timestamp", TimeStamp.now().millis)
    )
    override lazy val chainIndex: ChainIndex = ChainIndex.unsafe(1, 2)
    val chain00                              = ChainIndex.unsafe(0, 0)
    val depBlock0                            = emptyBlock(blockFlow, chain00)
    val depBlock1                            = emptyBlock(blockFlow, chain00)
    addAndCheck(blockFlow, depBlock0, depBlock1)

    val miner              = getGenesisLockupScript(chainIndex.to)
    val uncleBlockTemplate = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    addAndCheck(blockFlow, emptyBlock(blockFlow, chainIndex))
    val parent = emptyBlock(blockFlow, chainIndex)
    addAndCheck(blockFlow, parent)

    val uncle0 = mine(
      blockFlow,
      uncleBlockTemplate.copy(deps = uncleBlockTemplate.deps.replace(0, depBlock0.hash))
    )
    val uncle1 = mine(
      blockFlow,
      uncleBlockTemplate.copy(deps = uncleBlockTemplate.deps.replace(0, depBlock1.hash))
    )
    val blockTemplate = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    val (validUncle, invalidUncle) =
      if (parent.blockDeps.inDeps(0) == depBlock0.hash) {
        (uncle0, uncle1)
      } else {
        (uncle1, uncle0)
      }
    val ghostUncleHashes = AVector(validUncle.hash, invalidUncle.hash)
    val block0 =
      mine(
        blockFlow,
        blockTemplate.setGhostUncles(
          ghostUncleHashes.map(hash => SelectedGhostUncle(hash, miner, 1))
        )
      )
    addAndCheck(blockFlow, uncle0, uncle1)
    checkBlock(block0, blockFlow).leftValue is Right(InvalidGhostUncleDeps)

    val block1 =
      mine(
        blockFlow,
        blockTemplate.setGhostUncles(AVector(SelectedGhostUncle(validUncle.hash, miner, 2)))
      )
    checkBlock(block1, blockFlow).isRight is true

    val block2 =
      mine(
        blockFlow,
        blockTemplate.setGhostUncles(AVector(SelectedGhostUncle(invalidUncle.hash, miner, 1)))
      )
    checkBlock(block2, blockFlow).leftValue is Right(InvalidGhostUncleDeps)
  }

  it should "validate block with valid uncles" in new RhoneFixture {
    mineBlocks(ALPH.MaxGhostUncleSize)
    val blockTemplate = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    blockTemplate.ghostUncleHashes.length is ALPH.MaxGhostUncleSize
    (0 until blockTemplate.ghostUncleHashes.length).foreach { size =>
      val selectedUncles = blockTemplate.ghostUncleHashes.take(size).map { hash =>
        val lockupScript = blockFlow.getBlockUnsafe(hash).minerLockupScript
        SelectedGhostUncle(hash, lockupScript, 1)
      }
      val block = mine(blockFlow, blockTemplate.setGhostUncles(selectedUncles))
      checkBlock(block, blockFlow).isRight is true
    }
  }

  it should "validate block if uncle header used by another uncle block" in new RhoneFixture {
    val block10 = emptyBlock(blockFlow, chainIndex)
    val block11 = emptyBlock(blockFlow, chainIndex)
    addAndCheck(blockFlow, block10, block11)
    val hashesAtHeight1 = blockFlow.getHashes(chainIndex, 1).rightValue
    hashesAtHeight1.length is 2

    val block20 = emptyBlock(blockFlow, chainIndex)
    block20.parentHash is hashesAtHeight1.head
    block20.ghostUncleHashes.value.length is 0
    val block21Template = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    block21Template.ghostUncleHashes is hashesAtHeight1.tail
    val block21 = mine(blockFlow, block21Template)

    addAndCheck(blockFlow, block20)
    addAndCheck(blockFlow, emptyBlock(blockFlow, chainIndex))
    addAndCheck(blockFlow, block21)

    val blockTemplate = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    blockTemplate.ghostUncleHashes is sortGhostUncleHashes(block21.hash +: hashesAtHeight1.tail)
    addAndCheck(blockFlow, mine(blockFlow, blockTemplate))
  }

  it should "invalidate blocks with sequential txs for pre-rhone hardfork" in new Fixture {
    override val configValues =
      Map(("alephium.network.rhone-hard-fork-timestamp", TimeStamp.Max.millis))
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Leman
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
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Rhone

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

  trait PoLWCoinbaseFixture extends Fixture {
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
      val gas = GasEstimation.estimateWithP2PKHInputs(utxos.length, rewardOutputs.length + 1)
      val unsignedTx =
        blockFlow
          .polwCoinbase(lockupScript, unlockScript, rewardOutputs, reward.burntAmount, utxos, gas)
          .rightValue
      val preImage  = UnlockScript.PoLW.buildPreImage(lockupScript, minerLockupScript)
      val signature = SignatureSchema.sign(preImage, privateKey)
      Transaction.from(unsignedTx, AVector(signature))
    }

    private def randomPoLWTarget(blockTargetTime: Duration): Target = {
      val from = Target.from(HashRate.unsafe(BigInteger.ONE.shiftLeft(62)), blockTargetTime)
      val to   = Target.from(HashRate.unsafe(BigInteger.ONE.shiftLeft(61)), blockTargetTime)
      Target.unsafe(nextU256(U256.unsafe(from.value), U256.unsafe(to.value)).v)
    }

    def emptyPoLWBlock(uncleSize: Int): Block = {
      val (utxos, privateKey) = prepareUtxos()
      val blocks = (0 until (uncleSize + 1)).map(_ => emptyBlock(blockFlow, chainIndex))
      blocks.foreach(addAndCheck(blockFlow, _))
      val block           = mineBlockTemplate(blockFlow, chainIndex)
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

  it should "check PoLW coinbase tx" in new PoLWCoinbaseFixture {
    implicit val validator = (block: Block) => {
      val hardFork  = networkConfig.getHardFork(block.timestamp)
      val groupView = blockFlow.getMutableGroupView(chainIndex.from, block.blockDeps).rightValue
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
    override val configValues =
      Map(("alephium.network.rhone-hard-fork-timestamp", TimeStamp.Max.millis))
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Leman

    implicit val validator = (block: Block) => {
      val hardFork  = networkConfig.getHardFork(block.timestamp)
      val groupView = blockFlow.getMutableGroupView(chainIndex.from, block.blockDeps).rightValue
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
    lazy val randomMiner = assetLockupGen(chainIndex.from).sample.get

    def newBlock(miner: LockupScript.Asset): Block = {
      val template   = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
      val emptyBlock = Block(template.dummyHeader(), template.transactions)
      emptyBlock.Coinbase.output(o => o.copy(lockupScript = miner))
    }

    implicit lazy val validator = (block: Block) => {
      val hardFork  = networkConfig.getHardFork(block.timestamp)
      val groupView = blockFlow.getMutableGroupView(chainIndex.from, block.blockDeps).rightValue
      checkCoinbase(blockFlow, chainIndex, block, groupView, hardFork)
    }
  }

  it should "check miner for tesnet" in new TestnetFixture {
    override val configValues: Map[String, Any] = Map(
      ("alephium.network.network-id", 1),
      ("alephium.network.rhone-hard-fork-timestamp", 0)
    )
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Rhone
    networkConfig.networkId is NetworkId.AlephiumTestNet

    newBlock(whitelistedMiner).pass()
    newBlock(randomMiner).fail(InvalidTestnetMiner)
  }

  it should "not check miner for testnet pre-Rhone" in new TestnetFixture {
    override val configValues: Map[String, Any] =
      Map(
        ("alephium.network.network-id", 1),
        ("alephium.network.rhone-hard-fork-timestamp", TimeStamp.Max.millis)
      )
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Leman
    networkConfig.networkId is NetworkId.AlephiumTestNet

    newBlock(whitelistedMiner).pass()
    newBlock(randomMiner).pass()
  }

  it should "not check miner for devnet" in new TestnetFixture {
    override val configValues: Map[String, Any] =
      Map(("alephium.network.rhone-hard-fork-timestamp", TimeStamp.Max.millis))
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Leman
    networkConfig.networkId is NetworkId.AlephiumDevNet

    newBlock(whitelistedMiner).pass()
    newBlock(randomMiner).pass()
  }
}
