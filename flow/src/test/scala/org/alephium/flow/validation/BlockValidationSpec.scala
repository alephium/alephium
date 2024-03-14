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

import org.alephium.flow.FlowFixture
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.io.StoragesFixture
import org.alephium.protocol.{ALPH, Hash, Signature, SignatureSchema}
import org.alephium.protocol.config._
import org.alephium.protocol.model._
import org.alephium.protocol.vm
import org.alephium.protocol.vm.{GasBox, GasPrice, Method, StatefulScript}
import org.alephium.serde.serialize
import org.alephium.util.{AlephiumSpec, AVector, TimeStamp, U256}

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

  trait GenesisForkFixture extends Fixture {
    override val configValues = Map(
      ("alephium.network.leman-hard-fork-timestamp", TimeStamp.now().plusHoursUnsafe(1).millis),
      ("alephium.network.ghost-hard-fork-timestamp", TimeStamp.Max.millis)
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

  it should "validate coinbase transaction simple format" in new Fixture {
    val block           = emptyBlock(blockFlow, chainIndex)
    val (privateKey, _) = SignatureSchema.generatePriPub()
    val output0         = assetOutputGen.sample.get
    val emptyOutputs    = AVector.empty[AssetOutput]
    val emptySignatures = AVector.empty[Signature]
    val script          = StatefulScript.alwaysFail
    val testSignatures =
      AVector[Signature](SignatureSchema.sign(block.coinbase.unsigned.id, privateKey))

    implicit val validator                 = (blk: Block) => checkCoinbaseEasy(blk, 1)
    implicit val error: InvalidBlockStatus = InvalidCoinbaseFormat

    info("script")
    block.Coinbase.unsignedTx(_.copy(scriptOpt = None)).pass()
    block.Coinbase.unsignedTx(_.copy(scriptOpt = Some(script))).fail()

    info("gasAmount")
    block.Coinbase.unsignedTx(_.copy(gasAmount = minimalGas)).pass()
    block.Coinbase.unsignedTx(_.copy(gasAmount = GasBox.from(0).value)).fail()

    info("gasPrice")
    block.Coinbase.unsignedTx(_.copy(gasPrice = coinbaseGasPrice)).pass()
    block.Coinbase.unsignedTx(_.copy(gasPrice = GasPrice(U256.Zero))).fail()

    info("output length")
    block.Coinbase.unsignedTx(_.copy(fixedOutputs = AVector(output0))).pass()
    block.Coinbase.unsignedTx(_.copy(fixedOutputs = emptyOutputs)).fail()

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

    info("input signature")
    block.Coinbase.tx(_.copy(inputSignatures = emptySignatures)).pass()
    block.Coinbase.tx(_.copy(inputSignatures = testSignatures)).fail()

    info("contract signature")
    block.Coinbase.tx(_.copy(scriptSignatures = emptySignatures)).pass()
    block.Coinbase.tx(_.copy(scriptSignatures = testSignatures)).fail()
  }

  trait PreGhostForkFixture extends Fixture {
    override val configValues = Map(
      ("alephium.network.ghost-hard-fork-timestamp", TimeStamp.Max.millis)
    )
  }

  trait CoinbaseDataFixture extends Fixture {
    def block: Block
    def uncles: AVector[BlockHash]
    lazy val coinbaseData: CoinbaseData =
      CoinbaseData.from(chainIndex, block.timestamp, uncles, ByteString.empty)

    def wrongTimeStamp(ts: TimeStamp): ByteString = {
      serialize(CoinbaseData.from(chainIndex, ts, uncles, ByteString.empty))
    }

    def wrongChainIndex(chainIndex: ChainIndex): ByteString = {
      serialize(
        CoinbaseData.from(
          chainIndex,
          block.header.timestamp,
          uncles,
          ByteString.empty
        )
      )
    }
  }

  it should "check coinbase data for pre-ghost hardfork" in new CoinbaseDataFixture {
    override val configValues =
      Map(("alephium.network.ghost-hard-fork-timestamp", TimeStamp.Max.millis))
    networkConfig.getHardFork(TimeStamp.now()) isnot HardFork.Ghost

    implicit val validator = (blk: Block) => checkCoinbaseData(blk.chainIndex, blk).map(_ => ())

    val block  = emptyBlock(blockFlow, chainIndex)
    val uncles = AVector.empty
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
  }

  it should "check coinbase data for ghost hardfork" in new CoinbaseDataFixture {
    override val configValues = Map(("alephium.network.ghost-hard-fork-timestamp", 0))
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Ghost

    implicit val validator = (blk: Block) => checkCoinbaseData(blk.chainIndex, blk).map(_ => ())

    val uncleBlock = blockGen(chainIndex).sample.get
    val uncles     = AVector(uncleBlock.hash)
    val block = mineWithoutCoinbase(
      blockFlow,
      chainIndex,
      AVector.empty,
      TimeStamp.now(),
      uncles.map(hash => (hash, p2pkScriptGen(chainIndex.to).sample.get.lockup))
    )
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
  }

  it should "check coinbase locked amount" in new Fixture {
    val block              = emptyBlock(blockFlow, chainIndex)
    val consensusConfig    = consensusConfigs.getConsensusConfig(block.timestamp)
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
    override val configValues =
      Map(("alephium.network.ghost-hard-fork-timestamp", TimeStamp.Max.millis))
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
      Map(("alephium.network.ghost-hard-fork-timestamp", TimeStamp.Max.millis))
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
      Map(("alephium.network.ghost-hard-fork-timestamp", TimeStamp.Max.millis))
    override lazy val chainIndex: ChainIndex = ChainIndex.unsafe(0, 0)
    val newStorages =
      StoragesFixture.buildStorages(rootPath.resolveSibling(Hash.generate.toHexString))
    val genesisNetworkConfig = new NetworkConfigFixture.Default {
      override def lemanHardForkTimestamp: TimeStamp = TimeStamp.now().plusHoursUnsafe(1)
      override def ghostHardForkTimestamp: TimeStamp = TimeStamp.Max
    }.networkConfig
    val lemanNetworkConfig = new NetworkConfigFixture.Default {
      override def ghostHardForkTimestamp: TimeStamp = TimeStamp.Max
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
      StatefulScript.unsafe(AVector(Method(true, false, false, 0, 0, 0, AVector(vm.TxGasFee))))
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

  trait GhostFixture extends Fixture {
    override val configValues = Map(
      ("alephium.network.ghost-hard-fork-timestamp", TimeStamp.now().millis)
    )
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Ghost
    lazy val miner = getGenesisLockupScript(chainIndex.to)

    def mineBlocks(size: Int): AVector[BlockHash] = {
      val blocks = (0 to size).map(_ => emptyBlock(blockFlow, chainIndex))
      addAndCheck(blockFlow, blocks: _*)
      val hashes = blockFlow.getHashes(chainIndex, 1).rightValue
      hashes.length is size + 1
      hashes
    }

    def mineChain(size: Int): AVector[Block] = {
      val blockFlow = isolatedBlockFlow()
      val blocks = (0 until size).map(_ => {
        val block = emptyBlock(blockFlow, chainIndex)
        addAndCheck(blockFlow, block)
        block
      })
      AVector.from(blocks)
    }
  }

  it should "invalidate block with invalid uncles size" in new GhostFixture {
    val hashes        = mineBlocks(ALPH.MaxUncleSize + 1)
    val blockTemplate = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    blockTemplate.uncleHashes.length is ALPH.MaxUncleSize
    val uncleHashes = hashes.tail
    uncleHashes.length is ALPH.MaxUncleSize + 1
    val block = mine(blockFlow, blockTemplate.setUncles(uncleHashes.map((_, miner))))
    checkBlock(block, blockFlow).left.value isE InvalidUncleSize
  }

  it should "invalidate block with duplicate uncles" in new GhostFixture {
    mineBlocks(ALPH.MaxUncleSize)
    val blockTemplate = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    val block =
      mine(
        blockFlow,
        blockTemplate.setUncles(AVector.fill(2)((blockTemplate.uncleHashes.head, miner)))
      )
    checkBlock(block, blockFlow).left.value isE DuplicatedUncles
  }

  it should "invalidate block with used uncles" in new GhostFixture {
    mineBlocks(ALPH.MaxUncleSize)
    val block0 = mineBlockTemplate(blockFlow, chainIndex)
    addAndCheck(blockFlow, block0)

    val block1Template = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    block1Template.uncleHashes.isEmpty is true
    val block1 = mine(
      blockFlow,
      block1Template.setUncles(AVector((block0.uncleHashes.rightValue.head, miner)))
    )
    checkBlock(block1, blockFlow).left.value isE InvalidUncles
  }

  it should "invalidate block if uncle is sibling" in new GhostFixture {
    val block0 = emptyBlock(blockFlow, chainIndex)
    addAndCheck(blockFlow, block0)
    val block10 = emptyBlock(blockFlow, chainIndex)
    block10.parentHash is block0.hash

    val blockTemplate = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    val block11       = mine(blockFlow, blockTemplate.setUncles(AVector((block10.hash, miner))))
    block11.parentHash is block0.hash
    checkBlock(block11, blockFlow).left.value isE UncleDoesNotExist

    addAndCheck(blockFlow, block10)
    checkBlock(block11, blockFlow).left.value isE InvalidUncles
  }

  it should "invalidate block if uncle is ancestor" in new GhostFixture {
    val block0 = emptyBlock(blockFlow, chainIndex)
    addAndCheck(blockFlow, block0)

    var parentBlock = block0
    (0 until ALPH.MaxUncleAge).foreach { _ =>
      val blockTemplate = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
      val invalidBlock  = mine(blockFlow, blockTemplate.setUncles(AVector((block0.hash, miner))))
      invalidBlock.parentHash is parentBlock.hash
      checkBlock(invalidBlock, blockFlow).left.value isE InvalidUncles

      parentBlock = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, parentBlock)
    }
  }

  it should "invalidate block if uncles' parent is not from mainchain" in new GhostFixture {
    val (blocks0, blocks1) = (mineChain(4), mineChain(4))
    addAndCheck(blockFlow, blocks0.toSeq: _*)
    addAndCheck(blockFlow, blocks1.toSeq: _*)
    val hashes = blockFlow.getHashes(chainIndex, 3).rightValue
    hashes.length is 2
    val uncleHashes = hashes.tail

    val blockTemplate = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    blockTemplate.uncleHashes.length is 1
    val block = mine(
      blockFlow,
      blockTemplate.setUncles((blockTemplate.uncleHashes ++ uncleHashes).map((_, miner)))
    )
    checkBlock(block, blockFlow).left.value isE InvalidUncles
  }

  it should "invalidate block with invalid uncle intra deps" in new Fixture {
    override val configValues = Map(
      ("alephium.broker.broker-num", 1),
      ("alephium.network.ghost-hard-fork-timestamp", TimeStamp.now().millis)
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
    val block0 =
      mine(
        blockFlow,
        blockTemplate.setUncles(AVector((validUncle.hash, miner), (invalidUncle.hash, miner)))
      )
    addAndCheck(blockFlow, uncle0, uncle1)
    checkBlock(block0, blockFlow).leftValue is Right(InvalidUncleDeps)

    val block1 = mine(blockFlow, blockTemplate.setUncles(AVector((validUncle.hash, miner))))
    checkBlock(block1, blockFlow).isRight is true

    val block2 = mine(blockFlow, blockTemplate.setUncles(AVector((invalidUncle.hash, miner))))
    checkBlock(block2, blockFlow).leftValue is Right(InvalidUncleDeps)
  }

  it should "validate block with valid uncles" in new GhostFixture {
    mineBlocks(ALPH.MaxUncleSize)
    val blockTemplate = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    blockTemplate.uncleHashes.length is ALPH.MaxUncleSize
    (0 until blockTemplate.uncleHashes.length).foreach { size =>
      val block =
        mine(
          blockFlow,
          blockTemplate.setUncles(blockTemplate.uncleHashes.take(size).map((_, miner)))
        )
      checkBlock(block, blockFlow).isRight is true
    }
  }

  it should "validate block if uncle header used by another uncle block" in new GhostFixture {
    val block10 = emptyBlock(blockFlow, chainIndex)
    val block11 = emptyBlock(blockFlow, chainIndex)
    addAndCheck(blockFlow, block10, block11)
    val hashesAtHeight1 = blockFlow.getHashes(chainIndex, 1).rightValue
    hashesAtHeight1.length is 2

    val block20 = emptyBlock(blockFlow, chainIndex)
    block20.parentHash is hashesAtHeight1.head
    block20.uncleHashes.value.length is 0
    val block21Template = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    block21Template.uncleHashes is hashesAtHeight1.tail
    val block21 = mine(blockFlow, block21Template)

    addAndCheck(blockFlow, block20)
    addAndCheck(blockFlow, emptyBlock(blockFlow, chainIndex))
    addAndCheck(blockFlow, block21)

    val blockTemplate = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    blockTemplate.uncleHashes is block21.hash +: hashesAtHeight1.tail
    addAndCheck(blockFlow, mine(blockFlow, blockTemplate))
  }
}
