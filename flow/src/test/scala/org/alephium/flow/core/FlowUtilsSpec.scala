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

import org.alephium.crypto.Byte64
import org.alephium.flow.{FlowFixture, GhostUncleFixture}
import org.alephium.flow.core.ExtraUtxosInfo
import org.alephium.flow.mempool.{Normal, Reorg}
import org.alephium.flow.model.BlockFlowTemplate
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
        assets.map(asset => Byte64.from(SignatureSchema.sign(unsignedTx.id, asset.privateKey))),
        AVector.empty
      )
      val blockEnv = blockFlow.getDryrunBlockEnv(unsignedTx.chainIndex).rightValue

      val worldState = blockFlow.getBestCachedWorldState(groupIndex).rightValue
      assets.foreach { asset =>
        worldState
          .addAsset(
            asset.txInput.outputRef,
            asset.referredOutput,
            tx.id,
            None
          )
          .isRight is true
      }
      val firstInput = assets.head.referredOutput
      val firstOutput = firstInput.copy(
        amount = firstInput.amount.subUnsafe(tx.gasFeeUnsafe),
        additionalData = ByteString.empty
      )
      val bestDeps = blockFlow.getBestDepsPreDanube(groupIndex)
      val groupView =
        blockFlow.getMutableGroupViewPreDanube(groupIndex, bestDeps, worldState).rightValue
      blockFlow.generateFullTx(chainIndex, groupView, blockEnv, tx, script, 0).rightValue is
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
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))

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
    def testPrepareBlockFlow(allowConflictedTxs: Boolean) = {
      val fromGroup      = Random.nextInt(groups0)
      val chainIndex0    = ChainIndex.unsafe(fromGroup, Random.nextInt(groups0))
      val anotherToGroup = (chainIndex0.to.value + 1 + Random.nextInt(groups0 - 1)) % groups0
      val chainIndex1    = ChainIndex.unsafe(fromGroup, anotherToGroup)
      val block0         = transfer(blockFlow, chainIndex0)
      val block1         = transfer(blockFlow, chainIndex1)

      addAndCheck(blockFlow, block0)
      val groupIndex = GroupIndex.unsafe(fromGroup)
      val tx1        = block1.nonCoinbase.head.toTemplate
      blockFlow.isTxConflicted(groupIndex, tx1) is !allowConflictedTxs
      blockFlow.getGrandPool().add(chainIndex1, tx1, TimeStamp.now())

      val miner    = getGenesisLockupScript(chainIndex1.to)
      val template = blockFlow.prepareBlockFlowUnsafe(chainIndex1, miner)
      template.deps.contains(block0.hash) is false
      template.transactions.init.isEmpty is !allowConflictedTxs
    }
  }

  it should "detect tx conflicts using bestDeps for pre-rhone hardfork" in new TxConflictsFixture {
    override val configValues: Map[String, Any] = Map(
      ("alephium.consensus.mainnet.uncle-dependency-gap-time", "10 seconds"),
      ("alephium.broker.broker-num", 1)
    )
    setHardForkBefore(HardFork.Rhone)
    testPrepareBlockFlow(false)
  }

  it should "detect tx conflicts using bestDeps for rhone hardfork" in new TxConflictsFixture {
    override val configValues: Map[String, Any] = Map(
      ("alephium.consensus.rhone.uncle-dependency-gap-time", "10 seconds"),
      ("alephium.broker.broker-num", 1)
    )
    setHardFork(HardFork.Rhone)
    testPrepareBlockFlow(false)
  }

  it should "allow conflicted txs when preparing block template since danube" in new TxConflictsFixture {
    override val configValues: Map[String, Any] = Map(
      ("alephium.consensus.rhone.uncle-dependency-gap-time", "10 seconds"),
      ("alephium.broker.broker-num", 1)
    )
    setHardForkSince(HardFork.Danube)
    testPrepareBlockFlow(true)
  }

  trait DanubeConflictedTxsFixture extends FlowFixture {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))
    setHardForkSince(HardFork.Danube)

    val fromGroup   = GroupIndex.random
    val toGroup0    = GroupIndex.unsafe((fromGroup.value + 1) % groups0)
    val toGroup1    = GroupIndex.unsafe((fromGroup.value + 2) % groups0)
    val chainIndex0 = ChainIndex(fromGroup, toGroup0)
    val chainIndex1 = ChainIndex(fromGroup, toGroup1)
    val now         = TimeStamp.now()
    val block0      = transfer(blockFlow, chainIndex0, now)
    val block1      = transfer(blockFlow, chainIndex1, now.plusMillisUnsafe(1))
    addAndCheck(blockFlow, block0, block1)

    val block2 = emptyBlock(blockFlow, ChainIndex(fromGroup, fromGroup))
    block2.blockDeps.deps.contains(block0.hash) is true
    block2.blockDeps.deps.contains(block1.hash) is true
    addAndCheck(blockFlow, block2)
  }

  it should "add conflicted txs to storage since danube" in new DanubeConflictedTxsFixture {
    val storage      = blockFlow.conflictedTxsStorage
    val conflictedTx = block1.nonCoinbase.head
    storage.conflictedTxsReversedIndex.existsUnsafe(block0.hash) is false
    storage.conflictedTxsReversedIndex.getUnsafe(block1.hash) is AVector(
      nodeindexes.ConflictedTxsSource(block2.hash, AVector(conflictedTx.id))
    )
    storage.conflictedTxsPerIntraBlock.getUnsafe(block2.hash) is AVector(
      nodeindexes.ConflictedTxsPerBlock(block1.hash, AVector(conflictedTx.id))
    )
  }

  it should "ignore conflicted txs when updating state since danube" in new DanubeConflictedTxsFixture {
    val block3 = emptyBlock(blockFlow, ChainIndex(toGroup0, toGroup0))
    addAndCheck(blockFlow, block3)
    val worldState0 = blockFlow.getBestPersistedWorldState(toGroup0).rightValue
    val tx0         = block0.nonCoinbase.head
    worldState0.existOutput(tx0.outputRefs.head) isE true

    val block4 = emptyBlock(blockFlow, ChainIndex(toGroup1, toGroup1))
    addAndCheck(blockFlow, block4)
    val worldState1 = blockFlow.getBestPersistedWorldState(toGroup1).rightValue
    val tx1         = block1.nonCoinbase.head
    worldState1.existOutput(tx1.outputRefs.head) isE false
  }

  it should "update state if the conflicted tx from fork chain since danube" in new DanubeConflictedTxsFixture {
    val tx      = block1.nonCoinbase.head
    val sources = AVector(nodeindexes.ConflictedTxsSource(block2.hash, AVector(tx.id)))
    blockFlow.conflictedTxsStorage.conflictedTxsReversedIndex.getUnsafe(block1.hash) is sources

    val template0 = BlockFlowTemplate.from(block2, 1)
    val index     = groups0 - 1 + toGroup0.value
    val newDeps =
      template0.deps.replace(index, blockFlow.genesisHashes(fromGroup.value)(toGroup0.value))
    val template1 = template0.copy(deps = newDeps)
    val block3    = mine(blockFlow, template1)
    block3.blockDeps.deps.contains(block0.hash) is false
    block3.blockDeps.deps.contains(block1.hash) is true
    addAndCheck(blockFlow, block3)
    blockFlow.conflictedTxsStorage.conflictedTxsReversedIndex.getUnsafe(block1.hash) is sources

    val block4 = mineBlockWithDep(ChainIndex(toGroup1, toGroup1), block3.hash)
    addAndCheck(blockFlow, block4)
    val worldState = blockFlow.getPersistedWorldState(block4.hash).rightValue
    worldState.existOutput(tx.outputRefs.head) isE true
  }

  it should "support transfers using incoming block outputs in danube" in new FlowFixture {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))
    setHardFork(HardFork.Danube)

    val genesisKey                = genesisKeys(0)._1
    val (privateKey0, publicKey0) = GroupIndex.unsafe(0).generateKey
    val block0                    = transfer(blockFlow, genesisKey, publicKey0, ALPH.alph(10))
    addAndCheck(blockFlow, block0)

    val (privateKey1, publicKey1) = GroupIndex.unsafe(1).generateKey
    val block1                    = transfer(blockFlow, privateKey0, publicKey1, ALPH.alph(5))
    addAndCheck(blockFlow, block1)
    val block2 = emptyBlock(blockFlow, ChainIndex.unsafe(0, 0))
    block2.blockDeps.deps.contains(block1.hash) is true
    addAndCheck(blockFlow, block2)

    val block3 = transfer(blockFlow, privateKey1, publicKey1, ALPH.alph(1))
    block3.nonCoinbase.head.allInputRefs.head is block1.nonCoinbase.head.outputRefs.head
    addAndCheck(blockFlow, block3)
  }

  it should "update mempool properly in normal case: danube" in new FlowFixture {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))
    setHardFork(HardFork.Danube)

    val chainIndex = ChainIndex.unsafe(0, 1)
    val tx         = transfer(blockFlow, chainIndex).nonCoinbase.head
    blockFlow.grandPool.add(chainIndex, tx.toTemplate, TimeStamp.now())
    blockFlow.getMemPool(chainIndex).contains(tx.id) is true

    val miner     = getGenesisLockupScript(chainIndex.to)
    val template0 = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    template0.transactions.head.id is tx.id
    blockFlow.getMemPool(chainIndex).contains(tx.id) is true

    val template1 = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    template1.transactions.head.id is tx.id
    blockFlow.getMemPool(chainIndex).contains(tx.id) is true

    val block = mine(blockFlow, template1)
    addAndCheck(blockFlow, block)
    val template2 = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    template2.transactions.length is 1 // coinbase tx
    blockFlow.getMemPool(chainIndex).contains(tx.id) is false
  }

  it should "update mempool properly in reorg: danube" in new FlowFixture {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))
    setHardFork(HardFork.Danube)

    val chainIndex  = ChainIndex.unsafe(0, 1)
    val miner       = getGenesisLockupScript(chainIndex.to)
    val genesisKey  = genesisKeys(0)._1
    val toPublicKey = chainIndex.to.generateKey._2

    val (privateKey0, publicKey0) = chainIndex.from.generateKey
    addAndCheck(blockFlow, transfer(blockFlow, genesisKey, publicKey0, ALPH.alph(2)))
    val (privateKey1, publicKey1) = chainIndex.from.generateKey
    addAndCheck(blockFlow, transfer(blockFlow, genesisKey, publicKey1, ALPH.alph(2)))

    addAndCheck(blockFlow, emptyBlock(blockFlow, chainIndex))
    val block0 = transfer(blockFlow, privateKey0, toPublicKey, ALPH.oneAlph)
    val tx0    = block0.nonCoinbase.head
    val block1 = transfer(blockFlow, privateKey1, toPublicKey, ALPH.oneAlph)
    val tx1    = block1.nonCoinbase.head

    blockFlow.grandPool.add(chainIndex, tx0.toTemplate, TimeStamp.now())
    blockFlow.prepareBlockFlowUnsafe(chainIndex, miner).transactions.head.id is tx0.id
    addAndCheck(blockFlow, block0)
    val template0 = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    template0.transactions.length is 1 // coinbase tx
    blockFlow.getMemPool(chainIndex).contains(tx0.id) is false

    blockFlow.grandPool.add(chainIndex, tx1.toTemplate, TimeStamp.now())
    blockFlow.prepareBlockFlowUnsafe(chainIndex, miner).transactions.head.id is tx1.id
    addAndCheck(blockFlow, block1)
    val template1 = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    template1.transactions.head.id is tx1.id
    blockFlow.getMemPool(chainIndex).contains(tx0.id) is false
    blockFlow.getMemPool(chainIndex).contains(tx1.id) is true

    val block2 = mineBlockWithDep(chainIndex, block1.hash)
    addAndCheck(blockFlow, block2)
    val template2 = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    template2.transactions.head.id is tx0.id
    blockFlow.getMemPool(chainIndex).contains(tx0.id) is true
    blockFlow.getMemPool(chainIndex).contains(tx1.id) is false
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
      val block                   = transfer(blockFlow, genesisKey, publicKey, ALPH.alph(10))
      addAndCheck(blockFlow, block)
      block.coinbase.unsigned.gasAmount is minimalGas
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
      val bestDeps  = blockFlow.getBestDepsPreDanube(chainIndex.from)
      blockFlow.collectTransactions(chainIndex, groupView, bestDeps, hardFork) isE expected
    }

    val txs0 = prepareTxs(minimalGas.value)
    test(HardFork.Leman, txs0)
    test(HardFork.Rhone, txs0)

    val txs1 = prepareTxs(maximalGasPerBlockPreRhone.value / 5)
    test(HardFork.Leman, txs1.take(4))
    test(HardFork.Rhone, txs1.take(1))

    val txs2 = prepareTxs(maximalGasPerBlock.value / 5)
    test(HardFork.Leman, txs2)
    test(HardFork.Rhone, txs2.take(4))

    val txs3 = prepareTxs(maximalGasPerBlockPreRhone.subUnsafe(minimalGas).value / 5)
    test(HardFork.Leman, txs3.take(5))
    test(HardFork.Rhone, txs3.take(1))

    val txs4 = prepareTxs(maximalGasPerBlock.subUnsafe(minimalGas).value / 5)
    test(HardFork.Leman, txs4)
    test(HardFork.Rhone, txs4.take(5))
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

  it should "prepare block with correct coinbase reward for mainnet hardfork" in new CoinbaseRewardFixture {
    setHardFork(HardFork.Mainnet)
    val emptyBlock = mineFromMemPool(blockFlow, chainIndex)
    emptyBlock.coinbaseReward is consensusConfigs.mainnet.emission
      .reward(emptyBlock.header)
      .miningReward
    emptyBlock.coinbaseReward is consensusConfigs.mainnet.emission
      .rewardWrtTime(emptyBlock.timestamp, ALPH.LaunchTimestamp)
    addAndCheck(blockFlow, emptyBlock)

    val transferBlock = newTransferBlock()
    transferBlock.coinbaseReward is consensusConfigs.mainnet.emission
      .reward(transferBlock.header)
      .miningReward
      .addUnsafe(transferBlock.nonCoinbase.head.gasFeeUnsafe.divUnsafe(2))
    addAndCheck(blockFlow, transferBlock)
  }

  it should "prepare block with correct coinbase reward for leman hardfork" in new CoinbaseRewardFixture {
    setHardFork(HardFork.Leman)
    val transferBlock = newTransferBlock()
    transferBlock.coinbaseReward is consensusConfigs.mainnet.emission
      .reward(transferBlock.header)
      .miningReward
    addAndCheck(blockFlow, transferBlock)
  }

  it should "prepare block with correct coinbase reward for since-rhone hardfork" in new CoinbaseRewardFixture
    with SinceRhoneGhostUncleFixture {
    setHardForkSince(HardFork.Rhone)
    override lazy val chainIndex = ChainIndex.unsafe(0, 0)

    val hardFork   = networkConfig.getHardFork(TimeStamp.now())
    val emptyBlock = mineFromMemPool(blockFlow, chainIndex)
    val emission   = consensusConfigs.getConsensusConfig(hardFork).emission
    val miningReward = emission
      .reward(emptyBlock.header)
      .miningReward
    miningReward is emission.rewardWrtTime(emptyBlock.header.timestamp, ALPH.LaunchTimestamp)
    emptyBlock.coinbase.unsigned.fixedOutputs.length is 1
    val mainChainReward = Coinbase.calcMainChainRewardSinceRhone(hardFork, miningReward)
    emptyBlock.coinbaseReward is mainChainReward
    miningReward > mainChainReward is true
    addAndCheck(blockFlow, emptyBlock)

    val transferBlock = newTransferBlock()
    transferBlock.coinbaseReward is Coinbase.calcMainChainRewardSinceRhone(hardFork, miningReward)
    addAndCheck(blockFlow, transferBlock)

    {
      mineUncleBlocks(blockFlow, chainIndex, 1)
      val block = mineBlockTemplate(blockFlow, chainIndex)
      addAndCheck(blockFlow, block)
      val ghostUncleHashes = block.ghostUncleHashes.rightValue
      ghostUncleHashes.length is 1
      block.coinbase.unsigned.fixedOutputs.length is 2
      val uncleReward = block.coinbase.unsigned.fixedOutputs(1).amount
      val heightDiff =
        blockFlow.getHeightUnsafe(block.hash) - blockFlow.getHeightUnsafe(ghostUncleHashes.head)
      uncleReward is Coinbase.calcGhostUncleReward(mainChainReward, heightDiff)
      block.coinbaseReward is mainChainReward.addUnsafe(uncleReward.divUnsafe(32))
    }

    {
      mineUncleBlocks(blockFlow, chainIndex, 2)
      val block = mineBlockTemplate(blockFlow, chainIndex)
      addAndCheck(blockFlow, block)
      val ghostUncleHashes = block.ghostUncleHashes.rightValue
      ghostUncleHashes.length is 2
      block.coinbase.unsigned.fixedOutputs.length is 3
      val uncleRewards = ghostUncleHashes.mapWithIndex { case (ghostUncleHash, index) =>
        val uncleReward = block.coinbase.unsigned.fixedOutputs(index + 1).amount
        val heightDiff =
          blockFlow.getHeightUnsafe(block.hash) - blockFlow.getHeightUnsafe(ghostUncleHash)
        uncleReward is Coinbase.calcGhostUncleReward(mainChainReward, heightDiff)
        uncleReward
      }
      block.coinbaseReward is mainChainReward.addUnsafe(
        uncleRewards.fold(U256.Zero)(_ addUnsafe _).divUnsafe(32)
      )
    }
  }

  "the rhone block reward" should "be roughly twice the danube block reward" in new FlowFixture {
    val now = TimeStamp.now()
    override val configValues: Map[String, Any] = Map(
      ("alephium.network.danube-hard-fork-timestamp", now.plusSecondsUnsafe(1).millis)
    )
    val chainIndex = ChainIndex.unsafe(0, 0)
    val rhoneBlock = emptyBlock(blockFlow, chainIndex, now)
    networkConfig.getHardFork(rhoneBlock.timestamp) is HardFork.Rhone
    val danubeBlock = emptyBlock(blockFlow, chainIndex, now.plusSecondsUnsafe(1))
    networkConfig.getHardFork(danubeBlock.timestamp) is HardFork.Danube

    val rhoneReward        = rhoneBlock.coinbaseReward
    val doubleDanubeReward = danubeBlock.coinbaseReward * 2
    val tolerance          = rhoneReward / 20
    val diff = if (doubleDanubeReward > rhoneReward) {
      doubleDanubeReward - rhoneReward
    } else {
      rhoneReward - doubleDanubeReward
    }
    diff < tolerance is true

    addAndCheck(blockFlow, rhoneBlock, danubeBlock)
  }

  it should "prepare block template when txs are inter-dependent: pre-rhone" in new FlowFixture {
    setHardForkBefore(HardFork.Rhone)
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
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))
    setHardForkBefore(HardFork.Danube)

    val mainGroup = GroupIndex.unsafe(0)
    val deps0     = blockFlow.getBestDepsPreDanube(mainGroup)
    val block0    = transfer(blockFlow, ChainIndex.unsafe(0, 0))
    addAndCheck(blockFlow, block0)
    val block1 = transfer(blockFlow, ChainIndex.unsafe(0, 1))
    addAndCheck(blockFlow, block1)
    val block2 = transfer(blockFlow, ChainIndex.unsafe(0, 1))
    addAndCheck(blockFlow, block2)
    val deps1 = blockFlow.getBestDepsPreDanube(mainGroup)

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
    val deps2 = blockFlow.getBestDepsPreDanube(mainGroup)

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

      val oldDeps = blockFlow.getBestDepsPreDanube(chainIndex.from)
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
      blockFlow.getBalance(LockupScript.p2pkh(pubKey), Int.MaxValue, true).rightValue.totalAlph is
        ALPH.alph(2)
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
    lazy val chainIndex = ChainIndex.unsafe(1, 2)
    def prepare(): BlockHash = {
      val block0 = emptyBlock(blockFlow, chainIndex)
      val block1 = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, block0, block1)

      blockFlow.getMaxHeightByWeight(chainIndex).rightValue is 1
      val blockHashes = blockFlow.getHashes(chainIndex, 1).rightValue
      blockHashes.toSet is Set(block0.hash, block1.hash)
      blockHashes.last
    }
  }

  trait PreRhonePrepareBlockFlowFixture extends PrepareBlockFlowFixture {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))
    setHardForkBefore(HardFork.Rhone)
  }

  it should "prepare block without uncles before rhone hardfork" in new PreRhonePrepareBlockFlowFixture {
    prepare()
    val blockTemplate =
      blockFlow.prepareBlockFlowUnsafe(chainIndex, getGenesisLockupScript(chainIndex.to))

    val block = mine(blockFlow, blockTemplate)
    block.header.version is DefaultBlockVersion
    block.ghostUncleHashes.rightValue.isEmpty is true
    addAndCheck(blockFlow, block)
  }

  trait SinceRhoneGhostUncleFixture extends GhostUncleFixture with NoIndexModelGeneratorsLike {
    setHardForkSince(HardFork.Rhone)

    lazy val chainIndex = chainIndexGenForBroker(brokerConfig).sample.value
    lazy val miner      = getGenesisLockupScript(chainIndex.to)
  }

  it should "prepare block with uncles since rhone hardfork" in new SinceRhoneGhostUncleFixture {
    val ghostUncleHash = mineUncleBlocks(blockFlow, chainIndex, 1).head
    val blockTemplate =
      blockFlow.prepareBlockFlowUnsafe(chainIndex, getGenesisLockupScript(chainIndex.to))
    val block = mine(blockFlow, blockTemplate)
    block.header.version is DefaultBlockVersion
    block.ghostUncleHashes.rightValue is AVector(ghostUncleHash)
    addAndCheck(blockFlow, block)
  }

  it should "select uncles that deps are from mainchain" in new SinceRhoneGhostUncleFixture {
    val depGroupIndex = GroupIndex.unsafe((chainIndex.from.value + 1) % brokerConfig.groups)
    val depChainIndex = ChainIndex(depGroupIndex, depGroupIndex)
    val depBlock0     = emptyBlock(blockFlow, depChainIndex)
    val depBlock1     = emptyBlock(blockFlow, depChainIndex)
    addAndCheck(blockFlow, depBlock0, depBlock1)

    val uncleBlockTemplate = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    addAndCheck(blockFlow, emptyBlock(blockFlow, chainIndex))
    addAndCheck(blockFlow, emptyBlock(blockFlow, chainIndex))

    val depIndex = if (depGroupIndex.value > chainIndex.from.value) {
      depGroupIndex.value - 1
    } else {
      depGroupIndex.value
    }
    val depChainHashes = blockFlow.getHashes(depChainIndex, 1).rightValue
    val invalidDepHash =
      if (depChainHashes.head == depBlock0.hash) depBlock1.hash else depBlock0.hash
    val invalidUncle = mine(
      blockFlow,
      uncleBlockTemplate.copy(deps = uncleBlockTemplate.deps.replace(depIndex, invalidDepHash))
    )
    val validDepHash = blockFlow.getHashes(depChainIndex, 0).rightValue.head
    val validUncle = mine(
      blockFlow,
      uncleBlockTemplate.copy(deps = uncleBlockTemplate.deps.replace(depIndex, validDepHash))
    )
    addAndCheck(blockFlow, invalidUncle, validUncle)
    val block = mineBlockTemplate(blockFlow, chainIndex)
    block.ghostUncleHashes.rightValue is AVector(validUncle.hash)
    addAndCheck(blockFlow, block)
  }

  it should "select duplicate ghost uncles before danube" in new GhostUncleFixture with Generators {
    setHardFork(HardFork.Rhone)
    val chainIndex = chainIndexGenForBroker(brokerConfig).sample.value
    mineBlocks(blockFlow, chainIndex, ALPH.MaxGhostUncleAge)

    val uncleHeight0               = nextInt(1, ALPH.MaxGhostUncleAge - 2)
    val (ghostUncle0, ghostUncle1) = mineTwoGhostUnclesAt(blockFlow, chainIndex, uncleHeight0)
    val miner                      = getGenesisLockupScript(chainIndex.to)
    val blockTemplate0             = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    blockTemplate0.ghostUncleHashes.contains(ghostUncle0.hash) is true
    blockTemplate0.ghostUncleHashes.contains(ghostUncle1.hash) is true

    val uncleHeight1 = uncleHeight0 + 1
    val ghostUncle3  = mineValidGhostUncleBlockAt(blockFlow, chainIndex, uncleHeight1)
    val ghostUncle4  = mineDuplicateGhostUncleBlock(blockFlow, ghostUncle3)
    BlockHeader.fromSameTemplate(ghostUncle3.header, ghostUncle4.header) is true
    val blockTemplate1 = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    blockTemplate1.ghostUncleHashes.contains(ghostUncle3.hash) is true
    blockTemplate1.ghostUncleHashes.contains(ghostUncle4.hash) is true
  }

  it should "not select duplicate ghost uncles since danube" in new GhostUncleFixture
    with Generators {
    setHardForkSince(HardFork.Danube)
    val chainIndex = chainIndexGenForBroker(brokerConfig).sample.value
    mineBlocks(blockFlow, chainIndex, ALPH.MaxGhostUncleAge)

    val uncleHeight0                 = nextInt(1, ALPH.MaxGhostUncleAge - 3)
    val (ghostUncle00, ghostUncle01) = mineTwoGhostUnclesAt(blockFlow, chainIndex, uncleHeight0)
    val miner                        = getGenesisLockupScript(chainIndex.to)
    val blockTemplate0               = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    blockTemplate0.ghostUncleHashes.contains(ghostUncle00.hash) is false
    blockTemplate0.ghostUncleHashes.contains(ghostUncle01.hash) is true

    val uncleHeight1                 = uncleHeight0 + 1
    val (ghostUncle10, ghostUncle11) = mineTwoGhostUnclesAt(blockFlow, chainIndex, uncleHeight1)
    val blockTemplate1               = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    blockTemplate1.ghostUncleHashes.contains(ghostUncle00.hash) is false
    blockTemplate1.ghostUncleHashes.contains(ghostUncle01.hash) is true
    blockTemplate1.ghostUncleHashes.contains(ghostUncle10.hash) is false
    blockTemplate1.ghostUncleHashes.contains(ghostUncle11.hash) is true

    val uncleHeight2 = uncleHeight1 + 1
    val ghostUncle2  = mineValidGhostUncleBlockAt(blockFlow, chainIndex, uncleHeight2)
    val ghostUncle3  = mineDuplicateGhostUncleBlock(blockFlow, ghostUncle2)
    val ghostUncle4  = mineDuplicateGhostUncleBlock(blockFlow, ghostUncle2)
    BlockHeader.fromSameTemplate(ghostUncle2.header, ghostUncle3.header) is true
    BlockHeader.fromSameTemplate(ghostUncle2.header, ghostUncle4.header) is true
    val uncleHashes = blockFlow.getHashes(chainIndex, uncleHeight2).rightValue.tail
    uncleHashes.toSet is Set(ghostUncle2.hash, ghostUncle3.hash, ghostUncle4.hash)
    val blockTemplate2 = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    blockTemplate2.ghostUncleHashes.length is 2
    blockTemplate2.ghostUncleHashes.contains(uncleHashes.head) is true
    uncleHashes.tail.foreach(blockTemplate2.ghostUncleHashes.contains(_) is false)

    val block0 = mine(blockFlow, blockTemplate0)
    val block1 = mine(blockFlow, blockTemplate1)
    addAndCheck(blockFlow, block0, block1)
  }

  it should "not select the ghost uncle if it is a duplicates of used uncles" in new GhostUncleFixture
    with Generators {
    setHardForkSince(HardFork.Danube)
    val chainIndex = chainIndexGenForBroker(brokerConfig).sample.value
    mineBlocks(blockFlow, chainIndex, ALPH.MaxGhostUncleAge)

    val uncleHeight = nextInt(2, ALPH.MaxGhostUncleAge - 1)
    val ghostUncle0 = mineValidGhostUncleBlockAt(blockFlow, chainIndex, uncleHeight)
    val miner       = getGenesisLockupScript(chainIndex.to)
    val template0   = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    template0.ghostUncleHashes is AVector(ghostUncle0.hash)
    val block0 = mine(blockFlow, template0)
    addAndCheck(blockFlow, block0)

    val ghostUncle1 = mineDuplicateGhostUncleBlock(blockFlow, ghostUncle0)
    BlockHeader.fromSameTemplate(ghostUncle0.header, ghostUncle1.header) is true
    val template1 = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    template1.ghostUncleHashes.isEmpty is true
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

  it should "rebuild block template if there are invalid uncles" in new SinceRhoneGhostUncleFixture {
    val ghostUncleHash      = mineUncleBlocks(blockFlow, chainIndex, 1).head
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

  it should "rebuild block template if there are invalid txs and uncles" in new SinceRhoneGhostUncleFixture {
    mineUncleBlocks(blockFlow, chainIndex, 1)
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
    setHardFork(HardFork.Rhone)
    lazy val chainIndex = chainIndexGenForBroker(brokerConfig).retryUntil(_.isIntraGroup).sample.get

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
          defaultUtxoLimit,
          ExtraUtxosInfo.empty
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
      val bestDeps  = blockFlow.getBestDepsPreDanube(chainIndex.from)
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
    groupView.getPreAssetOutputInfo(tx1.unsigned.inputs.head.outputRef).rightValue.isDefined is true
    groupView
      .getPreAssetOutputInfo(tx1.unsigned.inputs.last.outputRef)
      .rightValue
      .isDefined is false
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
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))

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
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))
    setHardForkSince(HardFork.Rhone)

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

    val hardFork = networkConfig.getHardFork(TimeStamp.now())
    if (hardFork.isDanubeEnabled()) {
      addAndCheck(blockFlow, emptyBlock(blockFlow, chainIndex1))
    }

    val block1 = mineFromMemPool(blockFlow, chainIndex1)
    block1.nonCoinbase.head.id is tx0.id
    addAndCheck(blockFlow, block1)

    if (hardFork.isDanubeEnabled()) {
      addAndCheck(blockFlow, emptyBlock(blockFlow, chainIndex0))
    }

    val template1 =
      blockFlow.prepareBlockFlow(chainIndex0, LockupScript.p2pkh(publicKey2)).rightValue
    template1.transactions.init.map(_.toTemplate) is sequentialTxs
    addAndCheck(blockFlow, mine(blockFlow, template1))
  }

  it should "support sequential script txs in rhone" in new FlowFixture {
    setHardForkSince(HardFork.Rhone)
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

  it should "prepare block template with height" in new FlowFixture with Generators {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))

    val chainIndex = chainIndexGen.sample.get
    val miner      = getGenesisLockupScript(chainIndex.to)
    val bestTip    = blockFlow.getBlockChain(chainIndex).getBestTipUnsafe()
    blockFlow.getHeightUnsafe(bestTip) is 0

    (0 until 4).foreach { index =>
      val template = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
      template.height is index + 1
      val block = mineBlockTemplate(blockFlow, chainIndex)
      addAndCheck(blockFlow, block)
      blockFlow.getHeightUnsafe(block.hash) is template.height
    }
  }
}
