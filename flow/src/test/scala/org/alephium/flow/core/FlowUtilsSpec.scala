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

import org.alephium.flow.FlowFixture
import org.alephium.flow.mempool.{Normal, Reorg}
import org.alephium.flow.validation.BlockValidation
import org.alephium.protocol.{ALPH, Generators, PrivateKey, PublicKey, SignatureSchema}
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.ralph.Compiler
import org.alephium.util._

// scalastyle:off file.size.limit
class FlowUtilsSpec extends AlephiumSpec {
  it should "generate failed tx" in new FlowFixture with NoIndexModelGeneratorsLike {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val groupIndex = chainIndex.from

    forAll(
      assetsToSpendGen(tokensNumGen = Gen.choose(0, 1), scriptGen = p2pkScriptGen(groupIndex))
    ) { _assets =>
      val assets     = _assets.sortBy(_.referredOutput.amount).reverse
      val inputs     = assets.map(_.txInput)
      val script     = StatefulScript.alwaysFail
      val unsignedTx = UnsignedTransaction(txScriptOpt = Some(script), inputs, AVector.empty)
      val tx = TransactionTemplate(
        unsignedTx,
        assets.map(asset => SignatureSchema.sign(unsignedTx.id, asset.privateKey)),
        AVector.empty
      )

      val worldState = blockFlow.getBestCachedWorldState(groupIndex).rightValue
      assets.foreach { asset =>
        worldState.addAsset(asset.txInput.outputRef, asset.referredOutput).isRight is true
      }
      val firstInput = assets.head.referredOutput
      val firstOutput = firstInput.copy(
        amount = firstInput.amount.subUnsafe(tx.gasFeeUnsafe),
        additionalData = ByteString.empty
      )
      val bestDeps  = blockFlow.getBestDeps(groupIndex)
      val groupView = blockFlow.getMutableGroupView(groupIndex, bestDeps, worldState).rightValue
      val blockEnv  = blockFlow.getDryrunBlockEnv(unsignedTx.chainIndex).rightValue
      blockFlow.generateFullTx(chainIndex, groupView, blockEnv, tx, script).rightValue is
        Transaction(
          unsignedTx,
          scriptExecutionOk = false,
          AVector.empty,
          (firstOutput +: assets.tail.map(_.referredOutput.copy(additionalData = ByteString.empty)))
            .as[TxOutput],
          tx.inputSignatures,
          tx.scriptSignatures
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
      val consensusConfig = consensusConfigs.getConsensusConfig(block.timestamp)
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
    val tx0 = transactionGen(Gen.const(2)).sample.get
    val tx1 = transactionGen(Gen.const(2)).sample.get
    val tx2 = transactionGen(Gen.const(2)).sample.get
    FlowUtils.filterDoubleSpending(AVector(tx0, tx1, tx2)) is AVector(tx0, tx1, tx2)
    FlowUtils.filterDoubleSpending(AVector(tx0, tx0, tx2)) is AVector(tx0, tx2)
    FlowUtils.filterDoubleSpending(AVector(tx0, tx1, tx0)) is AVector(tx0, tx1)
    FlowUtils.filterDoubleSpending(AVector(tx0, tx2, tx2)) is AVector(tx0, tx2)
  }

  trait TxConflictsFixture extends FlowFixture {
    def test() = {
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
      blockFlow.getGrandPool().add(chainIndex1, tx1, TimeStamp.now())

      val miner    = getGenesisLockupScript(chainIndex1.to)
      val template = blockFlow.prepareBlockFlowUnsafe(chainIndex1, miner)
      template.deps.contains(block0.hash) is false
      template.transactions.init.isEmpty is true
    }
  }

  it should "detect tx conflicts using bestDeps for pre-rhone hardfork" in new TxConflictsFixture {
    override val configValues =
      Map(
        ("alephium.consensus.mainnet.uncle-dependency-gap-time", "10 seconds"),
        ("alephium.network.rhone-hard-fork-timestamp", TimeStamp.Max.millis),
        ("alephium.broker.broker-num", 1)
      )
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Leman
    test()
  }

  it should "detect tx conflicts using bestDeps for rhone hardfork" in new TxConflictsFixture {
    override val configValues =
      Map(
        ("alephium.consensus.rhone.uncle-dependency-gap-time", "10 seconds"),
        ("alephium.broker.broker-num", 1)
      )
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Rhone
    test()
  }

  it should "truncate txs w.r.t. tx number and gas" in new FlowFixture {
    val tx  = transfer(blockFlow, ChainIndex.unsafe(0, 0)).nonCoinbase.head.toTemplate
    val gas = tx.unsigned.gasAmount.value

    val txs = AVector(tx, tx)
    FlowUtils.truncateTxs(txs, 0, GasBox.unsafe(gas * 2)) is txs.take(0)
    FlowUtils.truncateTxs(txs, 1, GasBox.unsafe(gas * 2)) is txs.take(1)
    FlowUtils.truncateTxs(txs, 2, GasBox.unsafe(gas * 2)) is txs.take(2)
    FlowUtils.truncateTxs(txs, 3, GasBox.unsafe(gas * 2)) is txs.take(2)
    FlowUtils.truncateTxs(txs, 0, GasBox.unsafe(gas * 2 - 1)) is txs.take(0)
    FlowUtils.truncateTxs(txs, 1, GasBox.unsafe(gas * 2 - 1)) is txs.take(1)
    FlowUtils.truncateTxs(txs, 2, GasBox.unsafe(gas * 2 - 1)) is txs.take(1)
    FlowUtils.truncateTxs(txs, 3, GasBox.unsafe(gas * 2 - 1)) is txs.take(1)
  }

  it should "collect txs with respect to gas limit" in new FlowFixture {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val genesisKey = genesisKeys(chainIndex.from.value)._1
    val txNum      = 10
    val to         = chainIndex.to.generateKey._2
    val txs = AVector.from(0 until txNum).map { _ =>
      val (privateKey, publicKey) = chainIndex.to.generateKey
      addAndCheck(blockFlow, transfer(blockFlow, genesisKey, publicKey, ALPH.alph(10)))
      transfer(blockFlow, privateKey, to, ALPH.oneAlph).nonCoinbase.head
    }

    def prepareTxs(gas: Int) = {
      blockFlow.grandPool.clear()
      val gasAmount = GasBox.unsafe(gas)
      val now       = TimeStamp.now()
      txs.mapWithIndex { case (tx, index) =>
        val template = tx.copy(unsigned = tx.unsigned.copy(gasAmount = gasAmount)).toTemplate
        blockFlow.grandPool.add(chainIndex, template, now.plusMillisUnsafe(index.toLong))
        template
      }
    }

    def test(hardFork: HardFork, expected: AVector[TransactionTemplate]) = {
      val groupView = blockFlow.getMutableGroupView(chainIndex.from).rightValue
      val bestDeps  = blockFlow.getBestDeps(chainIndex.from)
      blockFlow.collectTransactions(chainIndex, groupView, bestDeps, hardFork) isE expected
    }

    val txs0 = prepareTxs(minimalGas.value)
    test(HardFork.Leman, txs0)
    test(HardFork.Rhone, txs0)

    val txs1 = prepareTxs(maximalGasPerBlockPreRhone.value / 5)
    test(HardFork.Leman, txs1.take(5))
    test(HardFork.Rhone, txs1.take(1))

    val txs2 = prepareTxs(maximalGasPerBlock.value / 5)
    test(HardFork.Leman, txs2)
    test(HardFork.Rhone, txs2.take(5))
  }

  trait CoinbaseRewardFixture extends FlowFixture {
    lazy val chainIndex = ChainIndex.unsafe(0, 0)

    def newTransferBlock() = {
      // generate the block using mineFromMemPool as it uses FlowUtils.prepareBlockFlow
      val tmpBlock = transfer(blockFlow, chainIndex)
      blockFlow
        .getGrandPool()
        .add(chainIndex, tmpBlock.nonCoinbase.head.toTemplate, TimeStamp.now())
      mineFromMemPool(blockFlow, chainIndex)
    }
  }

  it should "prepare block with correct coinbase reward for pre-rhone hardfork" in new CoinbaseRewardFixture {
    override val configValues = Map(
      ("alephium.network.rhone-hard-fork-timestamp", TimeStamp.Max.millis)
    )
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Leman
    val emptyBlock = mineFromMemPool(blockFlow, chainIndex)
    emptyBlock.coinbaseReward is consensusConfigs.mainnet.emission
      .reward(emptyBlock.header)
      .miningReward
    emptyBlock.coinbaseReward is ALPH.alph(30) / 9
    addAndCheck(blockFlow, emptyBlock)

    val transferBlock = newTransferBlock()
    transferBlock.coinbaseReward is consensusConfigs.mainnet.emission
      .reward(transferBlock.header)
      .miningReward
    addAndCheck(blockFlow, transferBlock)
  }

  it should "prepare block with correct coinbase reward for rhone hardfork" in new CoinbaseRewardFixture {
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Rhone
    val emptyBlock = mineFromMemPool(blockFlow, chainIndex)
    val miningReward = consensusConfigs.rhone.emission
      .reward(emptyBlock.header)
      .miningReward
    miningReward is (ALPH.alph(30) / 9 / 4)
    emptyBlock.coinbase.unsigned.fixedOutputs.length is 1
    val mainChainReward = Coinbase.calcMainChainReward(miningReward)
    emptyBlock.coinbaseReward is mainChainReward
    miningReward > mainChainReward is true
    addAndCheck(blockFlow, emptyBlock)

    val transferBlock = newTransferBlock()
    transferBlock.coinbaseReward is Coinbase.calcMainChainReward(miningReward)
    addAndCheck(blockFlow, transferBlock)

    {
      val block0 = emptyBlock(blockFlow, chainIndex)
      val block1 = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, block0, block1)
      blockFlow.getMaxHeightByWeight(chainIndex).rightValue is 3
      val block2          = mineBlockTemplate(blockFlow, chainIndex)
      val hashesAtHeight3 = blockFlow.getHashes(chainIndex, 3).rightValue
      block2.ghostUncleHashes.rightValue is hashesAtHeight3.tail
      block2.coinbase.unsigned.fixedOutputs.length is 2
      val uncleReward = block2.coinbase.unsigned.fixedOutputs(1).amount
      uncleReward is Coinbase.calcGhostUncleReward(mainChainReward, 1)
      block2.coinbaseReward is mainChainReward.addUnsafe(uncleReward.divUnsafe(32))
      addAndCheck(blockFlow, block2)
    }

    {
      val block0 = emptyBlock(blockFlow, chainIndex)
      val block1 = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, block0, block1)
      val block2 = emptyBlock(blockFlow, chainIndex)
      val block3 = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, block2, block3)
      val block4 = mineBlockTemplate(blockFlow, chainIndex)
      blockFlow.getMaxHeightByWeight(chainIndex).rightValue is 6
      val hashesAtHeight5  = blockFlow.getHashes(chainIndex, 5).rightValue
      val hashesAtHeight6  = blockFlow.getHashes(chainIndex, 6).rightValue
      val ghostUncleHashes = block4.ghostUncleHashes.rightValue
      ghostUncleHashes is
        AVector(hashesAtHeight6(1), hashesAtHeight5(1)).sortBy(_.bytes)(Bytes.byteStringOrdering)
      block4.coinbase.unsigned.fixedOutputs.length is 3
      val uncle0Reward = block4.coinbase.unsigned.fixedOutputs(1).amount
      val uncle1Reward = block4.coinbase.unsigned.fixedOutputs(2).amount
      if (ghostUncleHashes == AVector(hashesAtHeight6(1), hashesAtHeight5(1))) {
        uncle0Reward is Coinbase.calcGhostUncleReward(mainChainReward, 1)
        uncle1Reward is Coinbase.calcGhostUncleReward(mainChainReward, 2)
      } else {
        uncle0Reward is Coinbase.calcGhostUncleReward(mainChainReward, 2)
        uncle1Reward is Coinbase.calcGhostUncleReward(mainChainReward, 1)
      }
      block4.coinbaseReward is mainChainReward.addUnsafe(
        uncle0Reward.addUnsafe(uncle1Reward).divUnsafe(32)
      )
      addAndCheck(blockFlow, block4)
    }
  }

  it should "prepare block template when txs are inter-dependent: pre-rhone" in new FlowFixture {
    override val configValues =
      Map(("alephium.network.rhone-hard-fork-timestamp", TimeStamp.Max.millis))
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Leman

    val blockFlow1 = isolatedBlockFlow()
    val index      = ChainIndex.unsafe(0, 0)
    val block0     = transfer(blockFlow1, index)
    val tx0        = block0.nonCoinbase.head
    addAndCheck(blockFlow1, block0)
    val block1 = transfer(blockFlow1, index)
    val tx1    = block1.nonCoinbase.head
    addAndCheck(blockFlow1, block1)

    blockFlow.getGrandPool().add(index, AVector(tx0.toTemplate, tx1.toTemplate), TimeStamp.now())
    val miner = getGenesisLockupScript(index)
    blockFlow.prepareBlockFlowUnsafe(index, miner).transactions.init is AVector(tx0)
  }

  it should "include failed contract tx in block assembly" in new FlowFixture {
    val index = ChainIndex.unsafe(0, 0)
    blockFlow.getGrandPool().add(index, outOfGasTxTemplate, TimeStamp.now())
    val miner    = getGenesisLockupScript(index)
    val template = blockFlow.prepareBlockFlowUnsafe(index, miner)
    template.transactions.length is 2 // it should include the invalid tx
    template.transactions.map(_.id).contains(outOfGasTxTemplate.id) is true

    val validator  = BlockValidation.build(blockFlow)
    val worldState = validator.validateTemplate(index, template, blockFlow).rightValue.get
    worldState.persist().rightValue.contractState.rootHash is
      blockFlow.getBestPersistedWorldState(index.from).rightValue.contractState.rootHash
  }

  it should "reorg" in new FlowFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    val mainGroup = GroupIndex.unsafe(0)
    val deps0     = blockFlow.getBestDeps(mainGroup)
    val block0    = transfer(blockFlow, ChainIndex.unsafe(0, 0))
    addAndCheck(blockFlow, block0)
    val block1 = transfer(blockFlow, ChainIndex.unsafe(0, 1))
    addAndCheck(blockFlow, block1)
    val block2 = transfer(blockFlow, ChainIndex.unsafe(0, 1))
    addAndCheck(blockFlow, block2)
    val deps1 = blockFlow.getBestDeps(mainGroup)

    val blockFlow1 = isolatedBlockFlow()

    val block3 = transfer(blockFlow1, ChainIndex.unsafe(1, 1))
    addAndCheck(blockFlow1, block3)
    val block4 = transfer(blockFlow1, ChainIndex.unsafe(0, 0))
    addAndCheck(blockFlow1, block4)
    val block5 = transfer(blockFlow1, ChainIndex.unsafe(0, 1))
    addAndCheck(blockFlow1, block5)
    val block6 = transfer(blockFlow1, ChainIndex.unsafe(0, 2))
    addAndCheck(blockFlow1, block6)
    val block7 = transfer(blockFlow1, ChainIndex.unsafe(1, 0))
    addAndCheck(blockFlow1, block7)
    val block8 = transfer(blockFlow1, ChainIndex.unsafe(1, 1))
    addAndCheck(blockFlow1, block8)
    val block9 = transfer(blockFlow1, ChainIndex.unsafe(1, 0))
    addAndCheck(blockFlow1, block9)

    addAndCheck(blockFlow, block3)
    addAndCheck(blockFlow, block4)
    addAndCheck(blockFlow, block5)
    addAndCheck(blockFlow, block6)
    addAndCheck(blockFlow, block7)
    addAndCheck(blockFlow, block8)
    addAndCheck(blockFlow, block9)
    val deps2 = blockFlow.getBestDeps(mainGroup)

    blockFlow.calMemPoolChangesUnsafe(mainGroup, deps0, deps1) is
      Normal(
        AVector.from(
          brokerConfig.cliqueGroups
            .filter(_ != mainGroup)
            .map(fromGroup => ChainIndex(fromGroup, mainGroup) -> AVector.empty[Transaction])
        ) ++
          AVector
            .tabulate(groups0)(ChainIndex.unsafe(mainGroup.value, _) -> AVector.empty[Transaction])
            .replace(0, block0.chainIndex -> block0.nonCoinbase)
            .replace(1, block1.chainIndex -> (block1.nonCoinbase ++ block2.nonCoinbase))
      )
    blockFlow.calMemPoolChangesUnsafe(mainGroup, deps1, deps2) is
      Reorg(
        toRemove = AVector.from(
          brokerConfig.cliqueGroups
            .filter(_ != mainGroup)
            .map { fromGroup =>
              ChainIndex(fromGroup, mainGroup) ->
                (if (fromGroup.value == 1) {
                   block7.nonCoinbase
                 } else {
                   AVector.empty[Transaction]
                 })
            }
        ) ++
          AVector
            .tabulate(groups0)(ChainIndex.unsafe(mainGroup.value, _) -> AVector.empty[Transaction])
            .replace(0, block4.chainIndex -> block4.nonCoinbase)
            .replace(1, block5.chainIndex -> block5.nonCoinbase)
            .replace(2, block6.chainIndex -> block6.nonCoinbase),
        toAdd = AVector.from(
          brokerConfig.cliqueGroups
            .filter(_ != mainGroup)
            .map(fromGroup => ChainIndex(fromGroup, mainGroup) -> AVector.empty[Transaction])
        ) ++
          AVector
            .tabulate(groups0)(ChainIndex.unsafe(mainGroup.value, _) -> AVector.empty[Transaction])
            .replace(0, block0.chainIndex -> block0.nonCoinbase)
            .replace(1, block2.chainIndex -> (block2.nonCoinbase ++ block1.nonCoinbase))
      )
  }

  it should "update mempool when needed" in new FlowFixture {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val block0     = transfer(blockFlow, chainIndex)
    addAndCheck(blockFlow, block0)
    val block1    = transfer(blockFlow, chainIndex)
    val tx0       = block0.nonCoinbase.head.toTemplate
    val tx1       = block1.nonCoinbase.head.toTemplate
    val currentTs = TimeStamp.now()

    def test(heightGap: Int, expected: AVector[TransactionTemplate]) = {
      val blockFlow = isolatedBlockFlow()
      val grandPool = blockFlow.getGrandPool()
      val mempool   = blockFlow.getMemPool(chainIndex)
      grandPool.add(chainIndex, tx0, currentTs)
      grandPool.add(chainIndex, tx1, currentTs)
      mempool.contains(tx0.id) is true
      mempool.contains(tx1.id) is true
      mempool.isReady(tx0.id) is true
      mempool.isReady(tx1.id) is false

      val oldDeps = blockFlow.getBestDeps(chainIndex.from)
      addWithoutViewUpdate(blockFlow, block0)
      val newDeps = blockFlow.calBestDepsUnsafe(chainIndex.from)
      blockFlow.updateGrandPoolUnsafe(chainIndex.from, newDeps, oldDeps, heightGap)
      mempool.collectNonSequentialTxs(chainIndex, Int.MaxValue) is expected
    }

    test(0, AVector(tx0))
    test(1, AVector(tx1))
  }

  it should "assembly block when gas fees are different" in new FlowFixture {
    val chainIndex                  = ChainIndex.unsafe(0)
    val keys                        = Seq.tabulate(10)(_ => chainIndex.from.generateKey)
    val (fromPriKey, fromPubKey, _) = genesisKeys(0)
    keys.foreach { case (_, pubKey) =>
      val block = transfer(blockFlow, fromPriKey, pubKey, ALPH.alph(2))
      addAndCheck(blockFlow, block)
      blockFlow.getBalance(LockupScript.p2pkh(pubKey), Int.MaxValue, true).rightValue._1 is ALPH
        .alph(2)
    }

    val txs = keys.zipWithIndex.map { case ((priKey, _), index) =>
      val gasPrice = GasPrice(nonCoinbaseMinGasPrice.value + index)
      transferWithGas(
        blockFlow,
        priKey,
        fromPubKey,
        ALPH.oneAlph,
        gasPrice
      ).nonCoinbase.head.toTemplate
    }
    val timestamp = TimeStamp.now()
    Random
      .shuffle(txs)
      .foreach(tx => blockFlow.getGrandPool().add(chainIndex, tx, timestamp))
    val block = blockFlow.prepareBlockFlowUnsafe(chainIndex, LockupScript.p2pkh(fromPubKey))
    block.transactions.init.map(_.toTemplate) is AVector.from(txs.reverse)
  }

  it should "calculate difficulty" in new FlowFixture {
    blockFlow.getDifficultyMetric().rightValue is
      blockFlow.genesisBlocks.head.head.target.getDifficulty()
  }

  trait PrepareBlockFlowFixture extends FlowFixture {
    def rhoneHardForkTimestamp: TimeStamp

    override val configValues = Map(
      ("alephium.broker.broker-num", 1),
      ("alephium.network.rhone-hard-fork-timestamp", rhoneHardForkTimestamp.millis)
    )

    lazy val chainIndex = ChainIndex.unsafe(1, 2)
    def prepare(): BlockHash = {
      val block0 = emptyBlock(blockFlow, chainIndex)
      val block1 = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, block0, block1)

      blockFlow.getMaxHeightByWeight(chainIndex).rightValue is 1
      val blockHashes = blockFlow.getHashes(chainIndex, 1).rightValue
      blockHashes.length is 2
      blockHashes.toSet is Set(block0.hash, block1.hash)
      blockHashes.last
    }
  }

  it should "prepare block without uncles before rhone hardfork" in new PrepareBlockFlowFixture {
    def rhoneHardForkTimestamp: TimeStamp = TimeStamp.Max

    prepare()
    val blockTemplate =
      blockFlow.prepareBlockFlowUnsafe(chainIndex, getGenesisLockupScript(chainIndex.to))
    networkConfig.getHardFork(blockTemplate.templateTs) is HardFork.Leman

    val block = mine(blockFlow, blockTemplate)
    block.header.version is DefaultBlockVersion
    block.ghostUncleHashes.rightValue.isEmpty is true
    addAndCheck(blockFlow, block)
  }

  it should "prepare block with uncles after rhone hardfork" in new PrepareBlockFlowFixture {
    def rhoneHardForkTimestamp: TimeStamp = TimeStamp.now()

    val ghostUncleHash = prepare()
    val blockTemplate =
      blockFlow.prepareBlockFlowUnsafe(chainIndex, getGenesisLockupScript(chainIndex.to))
    networkConfig.getHardFork(blockTemplate.templateTs) is HardFork.Rhone

    val block = mine(blockFlow, blockTemplate)
    block.header.version is DefaultBlockVersion
    block.ghostUncleHashes.rightValue is AVector(ghostUncleHash)
    addAndCheck(blockFlow, block)
  }

  it should "select uncles that deps are from mainchain" in new PrepareBlockFlowFixture {
    def rhoneHardForkTimestamp: TimeStamp = TimeStamp.now()

    val chain00 = ChainIndex.unsafe(0, 0)
    chainIndex isnot chain00

    val depBlock0 = emptyBlock(blockFlow, chain00)
    val depBlock1 = emptyBlock(blockFlow, chain00)
    addAndCheck(blockFlow, depBlock0, depBlock1)

    val depHashes = blockFlow.getHashes(chain00, 1).rightValue
    depHashes.toSet is Set(depBlock0.hash, depBlock1.hash)

    val uncle0Template =
      blockFlow.prepareBlockFlowUnsafe(chainIndex, getGenesisLockupScript(chainIndex.to))
    val uncle1Template =
      blockFlow.prepareBlockFlowUnsafe(chainIndex, getGenesisLockupScript(chainIndex.to))
    val block0 = emptyBlock(blockFlow, chainIndex)
    addAndCheck(blockFlow, block0)
    val block1 = emptyBlock(blockFlow, chainIndex)
    addAndCheck(blockFlow, block1)

    val uncleDepHash = depHashes.filter(_ != block1.blockDeps.inDeps(0)).head
    val uncle0 =
      mine(blockFlow, uncle0Template.copy(deps = uncle0Template.deps.replace(0, uncleDepHash)))
    val uncle1 = mine(blockFlow, uncle1Template)
    addAndCheck(blockFlow, uncle0, uncle1)

    val block2 = mineBlockTemplate(blockFlow, chainIndex)
    block2.ghostUncleHashes.rightValue is AVector(uncle1.hash)
  }

  it should "rebuild block template if there are invalid txs" in new FlowFixture {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val block      = transfer(blockFlow, chainIndex)
    addAndCheck(blockFlow, block)

    val miner     = getGenesisLockupScript(chainIndex.to)
    val invalidTx = block.transactions.head
    blockFlow.grandPool.add(chainIndex, invalidTx.toTemplate, TimeStamp.now())
    val (template0, _) = blockFlow.createBlockTemplate(chainIndex, miner).rightValue
    val template1      = blockFlow.rebuild(template0, AVector(invalidTx), AVector.empty, miner)
    template1.transactions.head is invalidTx
    val template2 =
      blockFlow.validateTemplate(chainIndex, template1, AVector.empty, miner).rightValue
    template2 isnot template1
    template2 is blockFlow.rebuild(template1, AVector.empty, AVector.empty, miner)
  }

  it should "rebuild block template if there are invalid uncles" in new PrepareBlockFlowFixture {
    def rhoneHardForkTimestamp: TimeStamp = TimeStamp.now()

    val miner               = getGenesisLockupScript(chainIndex.to)
    val ghostUncleHash      = prepare()
    val (template0, uncles) = blockFlow.createBlockTemplate(chainIndex, miner).rightValue
    uncles.map(_.blockHash) is AVector(ghostUncleHash)
    blockFlow.validateTemplate(chainIndex, template0, uncles, miner).rightValue is template0

    val block = mine(blockFlow, template0)
    addAndCheck(blockFlow, block)

    val (template1, _) = blockFlow.createBlockTemplate(chainIndex, miner).rightValue
    val template2      = blockFlow.rebuild(template1, template1.transactions.init, uncles, miner)
    val template3      = blockFlow.validateTemplate(chainIndex, template2, uncles, miner).rightValue
    template3 isnot template2
    template3 is blockFlow.rebuild(template2, template2.transactions.init, AVector.empty, miner)
  }

  it should "rebuild block template if there are invalid txs and uncles" in new PrepareBlockFlowFixture {
    def rhoneHardForkTimestamp: TimeStamp = TimeStamp.now()

    prepare()
    val miner               = getGenesisLockupScript(chainIndex.to)
    val (template0, uncles) = blockFlow.createBlockTemplate(chainIndex, miner).rightValue
    val block0              = mine(blockFlow, template0)
    addAndCheck(blockFlow, block0)

    val block1 = transfer(blockFlow, chainIndex)
    addAndCheck(blockFlow, block1)
    val invalidTx = block1.transactions.head
    blockFlow.grandPool.add(chainIndex, invalidTx.toTemplate, TimeStamp.now())

    val (template1, _) = blockFlow.createBlockTemplate(chainIndex, miner).rightValue
    val template2      = blockFlow.rebuild(template1, AVector(invalidTx), uncles, miner)
    template2.transactions.head is invalidTx

    val template3 = blockFlow.validateTemplate(chainIndex, template2, uncles, miner).rightValue
    template3 isnot template2
    template3 is blockFlow.rebuild(template2, AVector.empty, AVector.empty, miner)
  }

  trait SequentialTxsFixture extends FlowFixture with Generators {
    lazy val chainIndex = chainIndexGenForBroker(brokerConfig).retryUntil(_.isIntraGroup).sample.get
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Rhone

    lazy val keys = (0 until 2).map { _ =>
      val (privateKey, publicKey) = chainIndex.from.generateKey
      val fromPrivateKey          = genesisKeys(chainIndex.from.value)._1
      val block                   = transfer(blockFlow, fromPrivateKey, publicKey, ALPH.alph(20))
      addAndCheck(blockFlow, block)
      (privateKey, publicKey)
    }
    lazy val (fromPrivateKey0, fromPublicKey0) = keys(0)
    lazy val (fromPrivateKey1, fromPublicKey1) = keys(1)
    private var timestamp                      = TimeStamp.now()

    def transferTx(
        from: PrivateKey,
        to: PublicKey,
        amount: U256,
        gasPrice: GasPrice = nonCoinbaseMinGasPrice
    ): TransactionTemplate = {
      val output =
        UnsignedTransaction.TxOutputInfo(LockupScript.p2pkh(to), amount, AVector.empty, None)
      val unsignedTx = blockFlow
        .transfer(
          from.publicKey,
          AVector(output),
          None,
          gasPrice,
          defaultUtxoLimit
        )
        .rightValue
        .rightValue
      val tx = Transaction.from(unsignedTx, from).toTemplate
      timestamp = timestamp.plusMillisUnsafe(1)
      blockFlow.grandPool.add(chainIndex, tx, timestamp)
      tx
    }

    def collectTxs() = {
      val groupView = blockFlow.getMutableGroupView(chainIndex.from).rightValue
      val bestDeps  = blockFlow.getBestDeps(chainIndex.from)
      blockFlow.collectTransactions(chainIndex, groupView, bestDeps, HardFork.Rhone).rightValue
    }
  }

  it should "collect sequential tx if the input is from parent tx" in new SequentialTxsFixture {
    val (_, toPublicKey0) = chainIndex.to.generateKey
    val tx0               = transferTx(fromPrivateKey0, toPublicKey0, ALPH.alph(5))
    val (_, toPublicKey1) = chainIndex.to.generateKey
    val tx1               = transferTx(fromPrivateKey0, toPublicKey1, ALPH.alph(5))

    tx1.unsigned.inputs.exists(input => tx0.fixedOutputRefs.contains(input.outputRef)) is true
    collectTxs() is AVector(tx0, tx1)
    val block = mineFromMemPool(blockFlow, chainIndex)
    block.nonCoinbase.map(_.toTemplate) is AVector(tx0, tx1)
    addAndCheck(blockFlow, block)
  }

  it should "collect sequential tx if inputs are from parent tx and persisted world state" in new SequentialTxsFixture {
    val block0 = transfer(blockFlow, fromPrivateKey0, fromPublicKey0, ALPH.alph(10))
    addAndCheck(blockFlow, block0)

    val (_, toPublicKey0) = chainIndex.to.generateKey
    val tx0               = transferTx(fromPrivateKey0, toPublicKey0, ALPH.alph(5))
    val (_, toPublicKey1) = chainIndex.to.generateKey
    val tx1               = transferTx(fromPrivateKey0, toPublicKey1, ALPH.alph(11))

    tx1.unsigned.inputs.length is 2
    val groupView = blockFlow.getImmutableGroupView(chainIndex.from).rightValue
    groupView.getAsset(tx1.unsigned.inputs.head.outputRef).rightValue.isDefined is true
    groupView.getAsset(tx1.unsigned.inputs.last.outputRef).rightValue.isDefined is false
    tx1.unsigned.inputs.last.outputRef is tx0.unsigned.fixedOutputRefs(1)
    collectTxs() is AVector(tx0, tx1)
    val block = mineFromMemPool(blockFlow, chainIndex)
    block.nonCoinbase.map(_.toTemplate) is AVector(tx0, tx1)
    addAndCheck(blockFlow, block)
  }

  it should "not collect sequential tx if its gas price is larger than parent tx" in new SequentialTxsFixture {
    val gasPrice          = GasPrice(nonCoinbaseMinGasPrice.value.addOneUnsafe())
    val (_, toPublicKey0) = chainIndex.to.generateKey
    val tx0               = transferTx(fromPrivateKey0, toPublicKey0, ALPH.alph(5))
    val (_, toPublicKey1) = chainIndex.to.generateKey
    val tx1               = transferTx(fromPrivateKey0, toPublicKey1, ALPH.alph(5), gasPrice)

    (tx1.unsigned.gasPrice.value > tx0.unsigned.gasPrice.value) is true
    tx1.unsigned.inputs.exists(input => tx0.fixedOutputRefs.contains(input.outputRef)) is true
    collectTxs() is AVector(tx0)
    val block = mineFromMemPool(blockFlow, chainIndex)
    block.nonCoinbase.map(_.toTemplate) is AVector(tx0)
    addAndCheck(blockFlow, block)

    blockFlow.getMemPool(chainIndex.from).contains(tx1.id) is true
  }

  it should "collect sequential txs based on gas price" in new SequentialTxsFixture {
    val (_, toPublicKey0) = chainIndex.to.generateKey
    val tx0               = transferTx(fromPrivateKey0, toPublicKey0, ALPH.alph(5))

    val gasPrice          = GasPrice(nonCoinbaseMinGasPrice.value.addOneUnsafe())
    val (_, toPublicKey2) = chainIndex.to.generateKey
    val tx1               = transferTx(fromPrivateKey1, toPublicKey2, ALPH.alph(5), gasPrice)

    (tx1.unsigned.gasPrice.value > tx0.unsigned.gasPrice.value) is true
    tx1.unsigned.inputs.exists(input => tx0.fixedOutputRefs.contains(input.outputRef)) is false
    collectTxs() is AVector(tx1, tx0)
    val block = mineFromMemPool(blockFlow, chainIndex)
    block.nonCoinbase.map(_.toTemplate) is AVector(tx1, tx0)
    addAndCheck(blockFlow, block)
  }

  it should "not collect sequential txs if the input of the parent tx does not exist" in new SequentialTxsFixture {
    val gasPrice          = GasPrice(nonCoinbaseMinGasPrice.value.addOneUnsafe())
    val (_, toPublicKey0) = chainIndex.to.generateKey
    val tx0               = transferTx(fromPrivateKey0, toPublicKey0, ALPH.alph(5))
    val (_, toPublicKey1) = chainIndex.to.generateKey
    val tx1               = transferTx(fromPrivateKey0, toPublicKey1, ALPH.alph(5), gasPrice)
    val tx2               = transferTx(fromPrivateKey0, toPublicKey1, ALPH.oneAlph)

    (tx1.unsigned.gasPrice.value > tx0.unsigned.gasPrice.value) is true
    tx1.unsigned.inputs.forall(input => tx0.fixedOutputRefs.contains(input.outputRef)) is true
    tx2.unsigned.inputs.forall(input => tx1.fixedOutputRefs.contains(input.outputRef)) is true
    collectTxs() is AVector(tx0)
    val block = mineFromMemPool(blockFlow, chainIndex)
    block.nonCoinbase.map(_.toTemplate) is AVector(tx0)
    addAndCheck(blockFlow, block)

    blockFlow.getMemPool(chainIndex.from).contains(tx1.id) is true
  }

  it should "not collect sequential txs for inter group blocks" in new SequentialTxsFixture {
    override lazy val chainIndex: ChainIndex =
      chainIndexGenForBroker(brokerConfig).retryUntil(!_.isIntraGroup).sample.get
    val (_, toPublicKey0) = chainIndex.to.generateKey
    val tx0               = transferTx(fromPrivateKey0, toPublicKey0, ALPH.alph(5))
    val (_, toPublicKey1) = chainIndex.to.generateKey
    val tx1               = transferTx(fromPrivateKey0, toPublicKey1, ALPH.alph(5))

    blockFlow.getMemPool(chainIndex).getAll().toSet is Set(tx0, tx1)

    val block = mineFromMemPool(blockFlow, chainIndex)
    block.nonCoinbase.map(_.toTemplate) is AVector(tx0)
    addAndCheck(blockFlow, block)
  }

  it should "collect all sequential txs" in new SequentialTxsFixture {
    val txs = AVector.from((0 until 15).map { _ =>
      val (_, toPublicKey) = chainIndex.to.generateKey
      transferTx(fromPrivateKey0, toPublicKey, ALPH.alph(1), nonCoinbaseMinGasPrice)
    })

    collectTxs() is txs
    val block = mineFromMemPool(blockFlow, chainIndex)
    addAndCheck(blockFlow, block)
    block.nonCoinbase.map(_.toTemplate) is txs
  }

  it should "collect random transfer txs" in new FlowFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    val chainIndex = ChainIndex.unsafe(0, 0)
    val keys = (0 until 4).map { _ =>
      val (privateKey, publicKey) = chainIndex.from.generateKey
      (0 until 10).foreach { _ =>
        val block = transfer(blockFlow, genesisKeys(0)._1, publicKey, ALPH.alph(5))
        addAndCheck(blockFlow, block)
      }
      (privateKey, publicKey)
    }

    @scala.annotation.tailrec
    def randomTransferTx: TransactionTemplate = {
      val fromIndex        = Random.nextInt(keys.length)
      val toIndex          = (fromIndex + 1) % keys.length
      val (fromKey, toKey) = (keys(fromIndex), keys(toIndex))
      val balance          = getAlphBalance(blockFlow, LockupScript.p2pkh(fromKey._2))
      if (balance < ALPH.oneAlph) {
        randomTransferTx
      } else {
        val transferAmount = balance.divUnsafe(U256.Two)
        transfer(blockFlow, fromKey._1, toKey._2, transferAmount).nonCoinbase.head.toTemplate
      }
    }

    val now = TimeStamp.now()
    val txs = (0 until 40).map { index =>
      val tx = randomTransferTx
      val ts = now.plusMillisUnsafe(index.toLong)
      blockFlow.grandPool.add(chainIndex, tx, ts)
      tx
    }

    val block = mineFromMemPool(blockFlow, chainIndex)
    addAndCheck(blockFlow, block)
    block.nonCoinbase.map(_.toTemplate).toSet is txs.toSet
  }

  it should "not collect sequential txs if the input of the source tx from other chains" in new FlowFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    val chainIndex0               = ChainIndex.unsafe(0, 0)
    val (privateKey0, publicKey0) = chainIndex0.from.generateKey
    val block0 = transfer(blockFlow, genesisKeys(0)._1, publicKey0, ALPH.alph(10))
    addAndCheck(blockFlow, block0)

    val chainIndex1     = ChainIndex.unsafe(0, 1)
    val (_, publicKey1) = GroupIndex.unsafe(1).generateKey
    val tx0 = transfer(blockFlow, privateKey0, publicKey1, ALPH.oneAlph).nonCoinbase.head.toTemplate
    val now = TimeStamp.now()
    blockFlow.grandPool.add(chainIndex1, tx0, now)

    val (_, publicKey2) = chainIndex0.from.generateKey
    var sourceTx        = tx0
    val sequentialTxs = AVector.from(0 until 5).map { index =>
      val tx =
        transfer(blockFlow, privateKey0, publicKey2, ALPH.oneAlph).nonCoinbase.head.toTemplate
      tx.unsigned.inputs.forall(input => sourceTx.fixedOutputRefs.contains(input.outputRef)) is true
      blockFlow.grandPool.add(chainIndex0, tx, now.plusMillisUnsafe(index.toLong))
      sourceTx = tx
      tx
    }

    val mempool      = blockFlow.grandPool.getMemPool(chainIndex0.from)
    val collectedTxs = mempool.collectAllTxs(chainIndex0, Int.MaxValue)
    collectedTxs is sequentialTxs

    val template0 =
      blockFlow.prepareBlockFlow(chainIndex0, LockupScript.p2pkh(publicKey2)).rightValue
    template0.transactions.length is 1 // coinbase tx

    addAndCheck(blockFlow, mineFromMemPool(blockFlow, chainIndex1))

    val template1 =
      blockFlow.prepareBlockFlow(chainIndex0, LockupScript.p2pkh(publicKey2)).rightValue
    template1.transactions.init.map(_.toTemplate) is sequentialTxs
    addAndCheck(blockFlow, mine(blockFlow, template1))
  }

  it should "support sequential script txs in rhone" in new FlowFixture {
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Rhone
    val chainIndex              = ChainIndex.unsafe(0, 0)
    val (privateKey, publicKey) = chainIndex.from.generateKey
    val lockupScript            = LockupScript.p2pkh(publicKey)
    val unlockScript            = UnlockScript.p2pkh(publicKey)
    val genesisKey              = genesisKeys(chainIndex.from.value)._1
    addAndCheck(blockFlow, transfer(blockFlow, genesisKey, publicKey, ALPH.alph(5)))
    val utxos = blockFlow.getUsableUtxos(lockupScript, Int.MaxValue).rightValue
    utxos.length is 1

    val script = Compiler.compileTxScript("TxScript Main { let _ = 0 }").rightValue
    val unsignedTx0 = UnsignedTransaction
      .buildScriptTx(
        script,
        lockupScript,
        unlockScript,
        utxos.map(info => (info.ref, info.output)),
        ALPH.cent(1),
        AVector.empty,
        minimalGas,
        nonCoinbaseMinGasPrice
      )
      .rightValue
    unsignedTx0.fixedOutputs.length is 1

    val unsignedTx1 = UnsignedTransaction
      .buildScriptTx(
        script,
        lockupScript,
        unlockScript,
        unsignedTx0.fixedOutputRefs.mapWithIndex { case (ref, index) =>
          (ref, unsignedTx0.fixedOutputs(index))
        },
        ALPH.cent(1),
        AVector.empty,
        minimalGas,
        nonCoinbaseMinGasPrice
      )
      .rightValue

    val tx0 = Transaction.from(unsignedTx0, privateKey).toTemplate
    val tx1 = Transaction.from(unsignedTx1, privateKey).toTemplate
    val now = TimeStamp.now()
    blockFlow.grandPool.add(chainIndex, tx0, now)
    blockFlow.grandPool.add(chainIndex, tx1, now.plusMillisUnsafe(1))
    val txs = AVector(tx0, tx1)

    val block = mineBlockTemplate(blockFlow, chainIndex)
    block.transactions.foreach(_.scriptExecutionOk is true)
    block.nonCoinbase.map(_.toTemplate) is txs
    addAndCheck(blockFlow, block)
  }
}
