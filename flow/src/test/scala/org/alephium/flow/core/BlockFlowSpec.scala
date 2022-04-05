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

import org.scalacheck.Gen
import org.scalatest.Assertion
import org.scalatest.concurrent.{Eventually, IntegrationPatience}

import org.alephium.flow.FlowFixture
import org.alephium.flow.core.BlockChain.TxIndex
import org.alephium.flow.core.BlockFlowState.{BlockCache, Confirmed}
import org.alephium.flow.io.StoragesFixture
import org.alephium.flow.setting.AlephiumConfigFixture
import org.alephium.protocol.{ALPH, BlockHash, Generators}
import org.alephium.protocol.config.GroupConfigFixture
import org.alephium.protocol.model._
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{AlephiumSpec, AVector, TimeStamp, U256, UnsecureRandom}

// scalastyle:off file.size.limit

class BlockFlowSpec extends AlephiumSpec {
  it should "compute correct blockflow height" in new FlowFixture {
    config.genesisBlocks.flatMap(identity).foreach { block =>
      blockFlow.getWeight(block.hash) isE Weight.zero
    }

    checkBalance(blockFlow, brokerConfig.groupRange.head, genesisBalance)
  }

  it should "work for at least 2 user group when adding blocks sequentially" in new FlowFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    if (brokerConfig.groups >= 2) {
      val chainIndex1 = ChainIndex.unsafe(0, 0)
      val block1      = transfer(blockFlow, chainIndex1)
      addAndCheck(blockFlow, block1, 1)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block1)
      checkBalance(blockFlow, 0, genesisBalance - ALPH.alph(1))

      val chainIndex2 = ChainIndex.unsafe(1, 1)
      val block2      = emptyBlock(blockFlow, chainIndex2)
      addAndCheck(blockFlow, block2, 2)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block2)
      checkBalance(blockFlow, 0, genesisBalance - ALPH.alph(1))

      val chainIndex3 = ChainIndex.unsafe(0, 1)
      val block3      = transfer(blockFlow, chainIndex3)
      addAndCheck(blockFlow, block3, 3)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block3)
      checkBalance(blockFlow, 0, genesisBalance - ALPH.alph(2))

      val chainIndex4 = ChainIndex.unsafe(0, 0)
      val block4      = emptyBlock(blockFlow, chainIndex4)
      addAndCheck(blockFlow, block4, 4)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block4)
      checkBalance(blockFlow, 0, genesisBalance - ALPH.alph(2))

      val chainIndex5 = ChainIndex.unsafe(0, 0)
      val block5      = transfer(blockFlow, chainIndex5)
      addAndCheck(blockFlow, block5, 5)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block5)
      checkBalance(blockFlow, 0, genesisBalance - ALPH.alph(3))
    }
  }

  it should "set proper initial bestDeps" in new FlowFixture {
    val validation = blockFlow.bestDeps.zipWithIndex.forall { case (bestDep, fromShift) =>
      val mainGroup = GroupIndex.unsafe(brokerConfig.groupRange(fromShift))
      blockFlow.checkFlowDepsUnsafe(bestDep, mainGroup)
    }
    validation is true
  }

  it should "compute cached blocks" in new FlowFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    val newBlocks = for {
      i <- 0 to 1
      j <- 0 to 1
    } yield transferOnlyForIntraGroup(blockFlow, ChainIndex.unsafe(i, j))
    newBlocks.foreach { block =>
      addAndCheck(blockFlow, block, 1)
    }

    val cache0 = blockFlow.getHashesForUpdates(GroupIndex.unsafe(0)).rightValue
    cache0.length is 1
    cache0.contains(newBlocks(0).hash) is false
    cache0.contains(newBlocks(1).hash) is true
    cache0.contains(newBlocks(2).hash) is false
    cache0.contains(newBlocks(3).hash) is false

    val block  = emptyBlock(blockFlow, ChainIndex.unsafe(0, 0))
    val cache1 = blockFlow.getBlocksForUpdates(block).rightValue.map(_.hash)
    cache1.contains(block.hash) is true
    cache1.contains(newBlocks(1).hash) is true
    cache1.contains(newBlocks(2).hash) is false
    cache1.contains(newBlocks(3).hash) is false
    block.blockDeps.inDeps(0) is newBlocks(3).hash
  }

  it should "work for at least 2 user group when adding blocks in parallel" in new FlowFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    if (brokerConfig.groups >= 2) {
      val blockFlow = genesisBlockFlow()

      val newBlocks1 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield transferOnlyForIntraGroup(blockFlow, ChainIndex.unsafe(i, j))
      newBlocks1.foreach { block =>
        addAndCheck(blockFlow, block, 1)
        blockFlow.getWeight(block) isE consensusConfig.minBlockWeight * 1
      }
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, newBlocks1)
      checkBalance(blockFlow, 0, genesisBalance - ALPH.alph(1))
      newBlocks1.map(_.hash).contains(blockFlow.getBestTipUnsafe()) is true

      val newBlocks2 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield transferOnlyForIntraGroup(blockFlow, ChainIndex.unsafe(i, j))
      newBlocks2.foreach { block => addAndCheck(blockFlow, block, 4) }
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, newBlocks2)
      checkBalance(blockFlow, 0, genesisBalance - ALPH.alph(2))
      newBlocks2.map(_.hash).contains(blockFlow.getBestTipUnsafe()) is true

      val newBlocks3 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield transferOnlyForIntraGroup(blockFlow, ChainIndex.unsafe(i, j))
      newBlocks3.foreach { block => addAndCheck(blockFlow, block, 8) }
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, newBlocks3)
      checkBalance(blockFlow, 0, genesisBalance - ALPH.alph(3))
      newBlocks3.map(_.hash).contains(blockFlow.getBestTipUnsafe()) is true
    }
  }

  it should "work for 2 user group when there is a fork" in new FlowFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    if (brokerConfig.groups >= 2) {
      val chainIndex1 = ChainIndex.unsafe(0, 0)
      val block11     = transfer(blockFlow, chainIndex1)
      val block12     = transfer(blockFlow, chainIndex1)
      addAndCheck(blockFlow, block11, 1)
      addAndCheck(blockFlow, block12, 1)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, IndexedSeq(block11, block12))
      blockFlow.grandPool.cleanAndExtractReadyTxs(
        blockFlow,
        TimeStamp.now()
      ) // remove double spending tx
      checkBalance(blockFlow, 0, genesisBalance - ALPH.alph(1))

      val block13 = transfer(blockFlow, chainIndex1)
      addAndCheck(blockFlow, block13, 2)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block13)
      checkBalance(blockFlow, 0, genesisBalance - ALPH.alph(2))

      val chainIndex2 = ChainIndex.unsafe(1, 1)
      val block21     = emptyBlock(blockFlow, chainIndex2)
      val block22     = emptyBlock(blockFlow, chainIndex2)
      addAndCheck(blockFlow, block21, 3)
      addAndCheck(blockFlow, block22, 3)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, IndexedSeq(block21, block22))
      checkBalance(blockFlow, 0, genesisBalance - ALPH.alph(2))

      val chainIndex3 = ChainIndex.unsafe(0, 1)
      val block3      = transfer(blockFlow, chainIndex3)
      addAndCheck(blockFlow, block3, 4)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block3)
      checkBalance(blockFlow, 0, genesisBalance - ALPH.alph(3))
    }
  }

  it should "compute genesis weight" in new FlowFixture {
    blockFlow.genesisBlocks.foreach {
      _.foreach { block =>
        blockFlow.getWeight(block) is blockFlow.calWeight(block)
      }
    }
  }

  it should "compute block weight" in new FlowFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    val blocks0 = for {
      from <- 0 until groups0
      to   <- 0 until groups0
    } yield emptyBlock(blockFlow, ChainIndex.unsafe(from, to))
    blocks0.foreach(addAndCheck(blockFlow, _, 1))

    val blocks1 = for {
      from <- 0 until groups0
      to   <- 0 until groups0
    } yield emptyBlock(blockFlow, ChainIndex.unsafe(from, to))
    blocks1.foreach(addAndCheck(blockFlow, _, brokerConfig.depsNum + 1))

    val blocks2 = for {
      from <- 0 until groups0
      to   <- 0 until groups0
    } yield emptyBlock(blockFlow, ChainIndex.unsafe(from, to))
    blocks2.foreach(addAndCheck(blockFlow, _, brokerConfig.chainNum + brokerConfig.depsNum + 1))

    val blocks3 = for {
      from <- 0 until groups0
      to   <- 0 until groups0
    } yield emptyBlock(blockFlow, ChainIndex.unsafe(from, to))
    blocks3.foreach(addAndCheck(blockFlow, _, brokerConfig.chainNum * 2 + brokerConfig.depsNum + 1))
  }

  it should "update mempool when there are conflicted txs" in new FlowFixture {
    if (brokerConfig.groups >= 2) {
      brokerConfig.groupRange.foreach { mainGroup =>
        val blockFlow  = genesisBlockFlow()
        val blockFlow1 = genesisBlockFlow()

        val chainIndex = ChainIndex.unsafe(mainGroup, 0)
        val block11    = transfer(blockFlow, chainIndex)
        val block12    = transfer(blockFlow1, chainIndex)
        blockFlow.grandPool.mempools.foreach(_.size is 0)
        addAndCheck(blockFlow, block11, 1)
        blockFlow.grandPool.mempools.foreach(_.size is 0)
        addAndCheck(blockFlow, block12, 1)

        val blockAdded = blockFlow.getBestDeps(chainIndex.from).getOutDep(chainIndex.to)
        if (blockAdded equals block12.hash) {
          val conflictedTx = block11.nonCoinbase.head
          blockFlow.getMemPool(chainIndex).size is 1 // the conflicted tx is kept
          blockFlow.getMemPool(chainIndex).contains(chainIndex, conflictedTx.id) is true
          val miner    = getGenesisLockupScript(chainIndex)
          val template = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
          template.transactions.length is 1 // the conflicted tx will not be used, only coinbase tx
          template.transactions.map(_.id).contains(conflictedTx.id) is false
        } else {
          blockAdded is block11.hash
        }
      }
    }
  }

  it should "reload blockflow properly from storage" in new FlowFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))
    val blockFlow0            = genesisBlockFlow()

    val newBlocks1 = for {
      i <- 0 to 1
      j <- 0 to 1
    } yield transferOnlyForIntraGroup(blockFlow0, ChainIndex.unsafe(i, j))
    newBlocks1.foreach { block => addAndCheck(blockFlow0, block, 1) }
    newBlocks1.map(_.hash).diff(blockFlow0.getAllTips.toArray).isEmpty is true

    val blockFlow1 = storageBlockFlow()
    newBlocks1.map(_.hash).diff(blockFlow1.getAllTips.toArray).isEmpty is true

    val newBlocks2 = for {
      i <- 0 to 1
      j <- 0 to 1
    } yield transferOnlyForIntraGroup(blockFlow1, ChainIndex.unsafe(i, j))
    newBlocks2.foreach { block => addAndCheck(blockFlow1, block, 4) }
    checkInBestDeps(GroupIndex.unsafe(0), blockFlow1, newBlocks2)
    checkBalance(blockFlow1, 0, genesisBalance - ALPH.alph(2))
    newBlocks2.map(_.hash).contains(blockFlow1.getBestTipUnsafe()) is true
  }

  it should "calculate hashes and blocks for update" in new FlowFixture {
    val block0 = emptyBlock(blockFlow, ChainIndex.unsafe(0, 0))
    addAndCheck(blockFlow, block0)
    val block1 = emptyBlock(blockFlow, ChainIndex.unsafe(0, 1))
    addAndCheck(blockFlow, block1)
    val block2 = emptyBlock(blockFlow, ChainIndex.unsafe(0, 0))
    addAndCheck(blockFlow, block2)

    val mainGroup = GroupIndex.unsafe(0)
    blockFlow.getHashesForUpdates(mainGroup) isE AVector.empty[BlockHash]
    blockFlow.getBlocksForUpdates(block2) isE AVector(block1, block2)
    val bestDeps0 = blockFlow.getBestDeps(mainGroup)
    blockFlow.getBlockCachesForUpdates(mainGroup, bestDeps0) isE AVector.empty[BlockCache]

    val block3 = emptyBlock(blockFlow, ChainIndex.unsafe(0, 1))
    addAndCheck(blockFlow, block3)
    val block4 = emptyBlock(blockFlow, ChainIndex.unsafe(0, 2))
    addAndCheck(blockFlow, block4)
    blockFlow.getHashesForUpdates(mainGroup) isE AVector(block3.hash, block4.hash)
    val bestDeps1 = blockFlow.getBestDeps(mainGroup)
    blockFlow.getBlockCachesForUpdates(mainGroup, bestDeps1) isE
      AVector(block3, block4).map(BlockFlowState.convertBlock(_, mainGroup))
  }

  behavior of "Sync"

  it should "compute sync locators and inventories for inter cliques" in new FlowFixture {
    brokerConfig.groupNumPerBroker is 1 // the test only works in this case

    (0 until brokerConfig.groups).foreach { testToGroup =>
      val blockFlow0    = isolatedBlockFlow()
      val testFromGroup = UnsecureRandom.sample(brokerConfig.groupRange)
      val blocks = (1 to 6).map { k =>
        val block =
          transferOnlyForIntraGroup(blockFlow0, ChainIndex.unsafe(testFromGroup, testToGroup))
        addAndCheck(blockFlow0, block, k)
        block
      }
      val hashes0 = AVector.from(blocks.map(_.hash))
      val locators0: AVector[(ChainIndex, AVector[BlockHash])] =
        AVector.tabulate(groupConfig.groups) { toGroup =>
          val hashes: AVector[BlockHash] = if (toGroup equals testToGroup) {
            AVector(
              hashes0(1),
              hashes0(3),
              hashes0(4),
              hashes0(5)
            )
          } else {
            AVector.empty
          }
          ChainIndex.unsafe(testFromGroup, toGroup) -> hashes
        }
      blockFlow0.getSyncLocators() isE locators0

      val blockFlow1 = isolatedBlockFlow()
      val locators1: AVector[(ChainIndex, AVector[BlockHash])] =
        AVector.tabulate(config.broker.groups)(toGroup =>
          ChainIndex.unsafe(testFromGroup, toGroup) -> AVector.empty[BlockHash]
        )
      blockFlow1.getSyncLocators() isE locators1

      blockFlow0.getSyncInventories(locators0.map(_._2), brokerConfig) isE
        AVector.fill(groupConfig.groups)(AVector.empty[BlockHash])
      blockFlow0.getSyncInventories(locators1.map(_._2), brokerConfig) isE
        AVector.tabulate(groupConfig.groups) { group =>
          if (group equals testToGroup) hashes0 else AVector.empty[BlockHash]
        }
      val locators2 = AVector.tabulate(groupConfig.groups)(_ => AVector.empty[BlockHash])
      val inventories = AVector.tabulate(groupConfig.groups) { group =>
        if (group equals testToGroup) {
          AVector.from(blocks.map(_.hash))
        } else {
          AVector.empty[BlockHash]
        }
      }
      blockFlow0.getSyncInventories(locators2, brokerConfig) isE inventories
      blockFlow1.getSyncInventories(locators0.map(_._2), brokerConfig) isE
        AVector.fill(groupConfig.groups)(AVector.empty[BlockHash])
      blockFlow1.getSyncInventories(locators1.map(_._2), brokerConfig) isE
        AVector.fill(groupConfig.groups)(AVector.empty[BlockHash])
    }
  }

  it should "compute sync locators and inventories for intra cliques" in new FlowFixture {
    override val configValues = Map(("alephium.broker.broker-id", 1))
    val blocks = AVector.tabulate(groups0) { toGroup =>
      val chainIndex = ChainIndex.unsafe(1, toGroup)
      val block      = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, block)
      block
    }
    blockFlow.getIntraSyncInventories() isE blocks.map(_.hash).map(AVector(_))
  }

  behavior of "Mining"

  it should "sanity check rewards" in new FlowFixture {
    val block = transferOnlyForIntraGroup(blockFlow, ChainIndex.unsafe(0, 0))
    block.nonCoinbase.nonEmpty is true
    val minimalReward = Seq(
      consensusConfig.emission.lowHashRateInitialRewardPerChain,
      consensusConfig.emission.stableMaxRewardPerChain
    ).min
    (block.coinbase.alphAmountInOutputs.get > minimalReward.subUnsafe(defaultGasFee)) is true
  }

  it should "reduce target gradually and reach a stable target eventually" in new FlowFixture {
    val chainIndex = ChainIndex.unsafe(0, 0)
    var lastTarget = consensusConfig.maxMiningTarget
    while ({
      val block = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, block)
      if (lastTarget != Target.Max) {
        if (block.target < lastTarget) {
          lastTarget = block.target
          true
        } else if (block.target equals lastTarget) {
          false
        } else {
          // the target is increasing, which is wrong
          assert(false)
          true
        }
      } else {
        lastTarget = block.target
        true
      }
    }) {}
  }

  trait DifficultyFixture extends FlowFixture {
    val chainIndex = ChainIndex.unsafe(0, 1)

    def prepareBlocks(scale: Int): Unit = {
      (0 until consensusConfig.powAveragingWindow + 1).foreach { k =>
        val block = emptyBlock(blockFlow, chainIndex)
        // we increase the difficulty for the last block of the DAA window (17 blocks)
        if (k equals consensusConfig.powAveragingWindow) {
          val newTarget = Target.unsafe(consensusConfig.maxMiningTarget.value.divide(scale))
          val newBlock  = block.copy(header = block.header.copy(target = newTarget))
          blockFlow.addAndUpdateView(reMine(blockFlow, chainIndex, newBlock), None)
        } else {
          addAndCheck(blockFlow, block)
          val bestDep = blockFlow.getBestDeps(chainIndex.from)
          blockFlow.getNextHashTarget(chainIndex, bestDep) isE consensusConfig.maxMiningTarget
        }
      }
    }
  }

  it should "calculate weighted target" in new DifficultyFixture {
    prepareBlocks(2)

    val bestDeps = blockFlow.getBestDeps(chainIndex.from)
    val nextTargetRaw = blockFlow
      .getHeaderChain(chainIndex)
      .getNextHashTargetRaw(bestDeps.uncleHash(chainIndex.to))
      .rightValue
      .value
    (BigInt(nextTargetRaw) < BigInt(consensusConfig.maxMiningTarget.value) / 2) is true
    val nextTargetClipped = blockFlow.getNextHashTarget(chainIndex, bestDeps).rightValue
    (nextTargetClipped > Target.unsafe(consensusConfig.maxMiningTarget.value / 2)) is true
  }

  it should "clip target" in new DifficultyFixture {
    prepareBlocks(8 * groups0)

    val bestDeps = blockFlow.getBestDeps(chainIndex.from)
    val nextTargetRaw = blockFlow
      .getHeaderChain(chainIndex)
      .getNextHashTargetRaw(bestDeps.uncleHash(chainIndex.to))
      .rightValue
      .value
    (BigInt(nextTargetRaw) < BigInt(consensusConfig.maxMiningTarget.value) / 2) is true
    val nextTargetClipped = blockFlow.getNextHashTarget(chainIndex, bestDeps).rightValue
    nextTargetClipped is Target.unsafe(consensusConfig.maxMiningTarget.value / 2)
  }

  behavior of "Balance"

  it should "transfer token inside a same group" in new FlowFixture {
    val testGroup = UnsecureRandom.sample(brokerConfig.groupRange)
    val block     = transferOnlyForIntraGroup(blockFlow, ChainIndex.unsafe(testGroup, testGroup))
    block.nonCoinbase.nonEmpty is true
    addAndCheck(blockFlow, block, 1)

    val pubScript = block.nonCoinbase.head.unsigned.fixedOutputs.head.lockupScript
    checkBalance(blockFlow, pubScript, ALPH.alph(1) - defaultGasFee)
    checkBalance(blockFlow, testGroup, genesisBalance - ALPH.alph(1))
  }

  trait InterGroupFixture extends FlowFixture { Test =>
    val anotherBroker = (brokerConfig.brokerId + 1 + Random.nextInt(
      brokerConfig.brokerNum - 1
    )) % brokerConfig.brokerNum
    val newConfigFixture = new AlephiumConfigFixture {
      override val configValues = Map(
        ("alephium.broker.broker-id", anotherBroker)
      )

      override lazy val genesisKeys = Test.genesisKeys
    }

    val anotherConfig   = newConfigFixture.config
    val anotherStorages = StoragesFixture.buildStorages(newConfigFixture.rootPath)
    config.genesisBlocks is anotherConfig.genesisBlocks
    val blockFlow0 = BlockFlow.fromGenesisUnsafe(config, storages)
    val blockFlow1 = BlockFlow.fromGenesisUnsafe(anotherConfig, anotherStorages)
  }

  it should "transfer token for inter-group transactions" in new InterGroupFixture {
    val fromGroup = UnsecureRandom.sample(brokerConfig.groupRange)
    val toGroup   = UnsecureRandom.sample(anotherConfig.broker.groupRange)

    val block = transfer(blockFlow0, ChainIndex.unsafe(fromGroup, toGroup))
    block.nonCoinbase.nonEmpty is true
    addAndCheck(blockFlow0, block, 1)
    checkBalance(blockFlow0, fromGroup, genesisBalance - ALPH.alph(1))
    addAndCheck(blockFlow1, block, 1)
    val pubScript = block.nonCoinbase.head.unsigned.fixedOutputs.head.lockupScript
    checkBalance(blockFlow1, pubScript, 0)

    val fromGroupBlock = emptyBlock(blockFlow0, ChainIndex.unsafe(fromGroup, fromGroup))
    addAndCheck(blockFlow0, fromGroupBlock, 2)
    addAndCheck(blockFlow1, fromGroupBlock.header, 2)
    checkBalance(blockFlow0, fromGroup, genesisBalance - ALPH.alph(1))
    checkBalance(blockFlow1, pubScript, ALPH.alph(1) - defaultGasFee)

    val toGroupBlock = emptyBlock(blockFlow1, ChainIndex.unsafe(toGroup, toGroup))
    addAndCheck(blockFlow1, toGroupBlock, 3)
    addAndCheck(blockFlow0, toGroupBlock.header, 3)
    checkBalance(blockFlow1, pubScript, ALPH.alph(1) - defaultGasFee)

    fromGroup isnot toGroup
    val newBlock = emptyBlock(blockFlow0, ChainIndex.unsafe(fromGroup, toGroup))
    addAndCheck(blockFlow0, newBlock, 4)
    addAndCheck(blockFlow1, newBlock, 4)
  }

  behavior of "Utilities"

  it should "find the best tip" in new InterGroupFixture {
    val fromGroup = UnsecureRandom.sample(brokerConfig.groupRange)

    val block = transfer(blockFlow0, ChainIndex.unsafe(fromGroup, fromGroup))
    addAndCheck(blockFlow0, block)
    addAndCheck(blockFlow1, block.header)

    blockFlow0.getBestIntraGroupTip() is block.hash
    blockFlow1.getBestIntraGroupTip() is block.hash
  }

  it should "cache blocks & headers during initialization" in new FlowFixture {
    blockFlow.getGroupCache(GroupIndex.unsafe(0)).size is 5

    val blockFlow1 = storageBlockFlow()
    blockFlow1.getGroupCache(GroupIndex.unsafe(0)).size is 5
    blockFlow1.blockHeaderChains.foreach(_.foreach { chain =>
      chain.headerCache.size is 1
      chain.stateCache.size is 1
    })

    (0 until consensusConfig.blockCacheCapacityPerChain).foreach { _ =>
      addAndCheck(blockFlow, emptyBlock(blockFlow, ChainIndex.unsafe(0, 1)))
    }

    val blockFlow2 = storageBlockFlow()
    blockFlow2.getGroupCache(GroupIndex.unsafe(0)).size is
      consensusConfig.blockCacheCapacityPerChain + 5
    blockFlow2.getHeaderChain(ChainIndex.unsafe(0, 1)).headerCache.size is
      consensusConfig.blockCacheCapacityPerChain + 1
    blockFlow2.getHeaderChain(ChainIndex.unsafe(0, 1)).stateCache.size is
      consensusConfig.blockCacheCapacityPerChain + 1
  }

  it should "generate random group orders" in new GroupConfigFixture {
    override def groups: Int = 3
    Seq(0, 1, 2).permutations.foreach { orders =>
      val hashGen = Gen
        .resultOf[Unit, BlockHash](_ => BlockHash.random)
        .retryUntil(hash => BlockFlow.randomGroupOrders(hash) equals AVector.from(orders))
      hashGen.sample.nonEmpty is true
    }
  }

  it should "prepare tx with lock time" in new FlowFixture {
    def test(lockTimeOpt: Option[TimeStamp]) = {
      val (_, publicKey, _) = genesisKeys(0)
      val (_, toPublicKey)  = GroupIndex.unsafe(1).generateKey
      val toLockupScript    = LockupScript.p2pkh(toPublicKey)

      val unsigned =
        blockFlow
          .transfer(
            publicKey,
            toLockupScript,
            lockTimeOpt,
            ALPH.alph(1),
            None,
            defaultGasPrice,
            defaultUtxoLimit
          )
          .rightValue
          .rightValue
      unsigned.fixedOutputs.length is 2
      unsigned.fixedOutputs(0).lockTime is lockTimeOpt.getOrElse(TimeStamp.zero)
      unsigned.fixedOutputs(1).lockTime is TimeStamp.zero
    }

    test(None)
    test(Some(TimeStamp.unsafe(1)))
    test(Some(TimeStamp.now()))
  }

  it should "spend locked outputs" in new FlowFixture with Eventually with IntegrationPatience {
    val lockTime       = TimeStamp.now().plusSecondsUnsafe(5)
    val block          = transfer(blockFlow, ChainIndex.unsafe(0, 0), lockTimeOpt = Some(lockTime))
    val toLockupScript = block.nonCoinbase.head.unsigned.fixedOutputs.head.lockupScript
    val toPrivateKey   = keyManager(toLockupScript)

    addAndCheck(blockFlow, block)
    val lockedBalance = ALPH.alph(1) - defaultGasFee
    blockFlow.getBalance(toLockupScript, Int.MaxValue) is Right((lockedBalance, lockedBalance, 1))

    blockFlow
      .transfer(
        toPrivateKey.publicKey,
        toLockupScript,
        None,
        ALPH.nanoAlph(1000),
        None,
        defaultGasPrice,
        defaultUtxoLimit
      )
      .rightValue
      .leftValue
      .startsWith("Not enough balance") is true
    eventually {
      blockFlow
        .transfer(
          toPrivateKey.publicKey,
          toLockupScript,
          None,
          ALPH.nanoAlph(1000),
          None,
          defaultGasPrice,
          defaultUtxoLimit
        )
        .rightValue
        .isRight is true
    }
  }

  it should "handle sequential txs" in new FlowFixture {
    override val configValues                   = Map(("alephium.broker.broker-num", 1))
    val fromGroup                               = GroupIndex.unsafe(Random.nextInt(groupConfig.groups))
    val (fromPriKey, fromPubKey, initialAmount) = genesisKeys(fromGroup.value)
    val fromLockup                              = LockupScript.p2pkh(fromPubKey)
    val theMemPool                              = blockFlow.getMemPool(fromGroup)

    var txCount = 0
    def transfer(): TransactionTemplate = {
      txCount += 1

      val toGroup        = GroupIndex.unsafe(Random.nextInt(groupConfig.groups))
      val chainIndex     = ChainIndex(fromGroup, toGroup)
      val (_, toPubKey)  = toGroup.generateKey
      val toLockupScript = LockupScript.p2pkh(toPubKey)
      val unsignedTx = blockFlow
        .transfer(
          fromPubKey,
          toLockupScript,
          None,
          ALPH.oneAlph,
          None,
          defaultGasPrice,
          defaultUtxoLimit
        )
        .rightValue
        .rightValue
      val tx = TransactionTemplate.from(unsignedTx, fromPriKey)

      tx.chainIndex is chainIndex
      theMemPool.addNewTx(chainIndex, tx, TimeStamp.now())
      theMemPool.contains(tx.chainIndex, tx.id) is true

      val balance = initialAmount - (ALPH.oneAlph + defaultGasFee).mulUnsafe(txCount)
      blockFlow.getBalance(fromLockup, Int.MaxValue).rightValue is ((balance, U256.Zero, 1))

      tx
    }

    val tx0         = transfer()
    val tx1         = transfer()
    val tx2         = transfer()
    val fromBalance = blockFlow.getBalance(fromLockup, Int.MaxValue).rightValue
    theMemPool.pendingPool.contains(tx0.id) is false
    theMemPool.pendingPool.contains(tx1.id) is true
    theMemPool.pendingPool.contains(tx2.id) is true

    val block0 = mineFromMemPool(blockFlow, tx0.chainIndex)
    addAndCheck(blockFlow, block0)
    theMemPool.contains(tx0.chainIndex, tx0.id) is false
    theMemPool.contains(tx1.chainIndex, tx1.id) is true
    theMemPool.pendingPool.contains(tx1.id) is false
    theMemPool.pendingPool.contains(tx2.id) is true
    blockFlow.getBestDeps(fromLockup.groupIndex).deps.contains(block0.hash) is true
    blockFlow.getBalance(fromLockup, Int.MaxValue).rightValue is fromBalance

    val block1 = mineFromMemPool(blockFlow, tx1.chainIndex)
    addAndCheck(blockFlow, block1)
    theMemPool.contains(tx1.chainIndex, tx1.id) is false
    theMemPool.contains(tx2.chainIndex, tx2.id) is true
    theMemPool.pendingPool.contains(tx2.id) is false
    blockFlow.getBestDeps(fromLockup.groupIndex).deps.contains(block1.hash) is true
    blockFlow.getBalance(fromLockup, Int.MaxValue).rightValue is fromBalance

    val block2 = mineFromMemPool(blockFlow, tx2.chainIndex)
    addAndCheck(blockFlow, block2)
    theMemPool.contains(tx2.chainIndex, tx2.id) is false
    blockFlow.getBestDeps(fromLockup.groupIndex).deps.contains(block2.hash) is true
    blockFlow.getBalance(fromLockup, Int.MaxValue).rightValue is fromBalance
  }

  it should "fetch bocks for the corresponding groups" in {
    trait Fixture extends FlowFixture {
      def test(): Assertion = {
        val blocks = blockFlow
          .getHeightedBlocks(
            ALPH.GenesisTimestamp.plusMinutesUnsafe(-1),
            ALPH.GenesisTimestamp.plusMinutesUnsafe(1)
          )
          .rightValue
          .map(_._2)
        val expected = brokerConfig.groupRange.flatMap { fromGroup =>
          (0 until groups0).map { toGroup =>
            AVector(blockFlow.genesisBlocks(fromGroup)(toGroup) -> 0)
          }
        }
        blocks is AVector.from(expected)
      }
    }

    new Fixture {
      test()
    }

    new Fixture {
      override val configValues = Map(("alephium.broker.broker-num", 1))
      test()
    }
  }

  behavior of "confirmations"

  it should "return correct confirmations for genesis txs" in new FlowFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    blockFlow.genesisBlocks.foreachWithIndex { case (blocks, from) =>
      blocks.foreachWithIndex { case (block, to) =>
        block.transactions.foreachWithIndex { case (tx, index) =>
          from is to
          blockFlow.getTxStatus(tx.id, ChainIndex.unsafe(from, to)) isE
            Some(Confirmed(TxIndex(block.hash, index), 1, 1, 1))
        }
      }
    }

    val newBlocks = for {
      from <- 0 until groups0
      to   <- 0 until groups0
    } yield {
      emptyBlock(blockFlow, ChainIndex.unsafe(from, to))
    }

    val newBlocks0  = Gen.someOf(newBlocks).retryUntil(_.nonEmpty).sample.get
    val newIndexes0 = newBlocks0.map(_.chainIndex)
    val newBlocks1  = newBlocks.filterNot(newBlocks0.contains)

    def count(bool: Boolean): Int = if (bool) 1 else 0

    newBlocks0.foreach(addAndCheck(blockFlow, _))
    blockFlow.genesisBlocks.foreachWithIndex { case (blocks, from) =>
      blocks.foreachWithIndex { case (block, to) =>
        block.transactions.foreachWithIndex { case (tx, index) =>
          val chainConfirmations = 1 +
            count(newIndexes0.contains(ChainIndex.unsafe(from, to)))
          val fromConfirmations = 1 +
            count(newIndexes0.contains(ChainIndex.unsafe(from, from)))
          val toConfirmations = 1 +
            count(newIndexes0.contains(ChainIndex.unsafe(to, to)))
          blockFlow.getTxStatus(tx.id, ChainIndex.unsafe(from, to)) isE
            Some(
              Confirmed(
                TxIndex(block.hash, index),
                chainConfirmations,
                fromConfirmations,
                toConfirmations
              )
            )
        }
      }
    }

    newBlocks1.foreach(addAndCheck(blockFlow, _))
    blockFlow.genesisBlocks.foreachWithIndex { case (blocks, from) =>
      blocks.foreachWithIndex { case (block, to) =>
        block.transactions.foreachWithIndex { case (tx, index) =>
          blockFlow.getTxStatus(tx.id, ChainIndex.unsafe(from, to)) isE
            Some(Confirmed(TxIndex(block.hash, index), 2, 2, 2))
        }
      }
    }
  }

  it should "return correct confirmations for intra group txs" in new FlowFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    for {
      targetGroup <- 0 until groups0
    } {
      val blockFlow0 = isolatedBlockFlow()
      val chainIndex = ChainIndex.unsafe(targetGroup, targetGroup)
      val block      = transfer(blockFlow0, chainIndex)
      blockFlow0.getTxStatus(block.transactions.head.id, chainIndex) isE None

      addAndCheck(blockFlow0, block)
      blockFlow0.getTxStatus(block.transactions.head.id, chainIndex) isE
        Some(Confirmed(TxIndex(block.hash, 0), 1, 1, 1))
    }
  }

  it should "return correct confirmations for inter group txs" in new FlowFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    for {
      from <- 0 until groups0
      to   <- 0 until groups0
      if from != to
    } {
      val blockFlow0 = isolatedBlockFlow()
      val chainIndex = ChainIndex.unsafe(from, to)
      val block0     = transfer(blockFlow0, chainIndex)
      blockFlow0.getTxStatus(block0.transactions.head.id, chainIndex) isE None

      addAndCheck(blockFlow0, block0)
      blockFlow0.getTxStatus(block0.transactions.head.id, chainIndex) isE
        Some(Confirmed(TxIndex(block0.hash, 0), 1, 0, 0))

      val block1 = emptyBlock(blockFlow0, ChainIndex.unsafe(from, from))
      addAndCheck(blockFlow0, block1)
      blockFlow0.getTxStatus(block0.transactions.head.id, chainIndex) isE
        Some(Confirmed(TxIndex(block0.hash, 0), 1, 1, 0))

      val block2 = emptyBlock(blockFlow0, ChainIndex.unsafe(to, to))
      addAndCheck(blockFlow0, block2)
      blockFlow0.getTxStatus(block0.transactions.head.id, chainIndex) isE
        Some(Confirmed(TxIndex(block0.hash, 0), 1, 1, 1))
    }
  }

  it should "not include new block as dependency when dependency gap time is large" in new FlowFixture {
    override val configValues =
      Map(
        ("alephium.consensus.uncle-dependency-gap-time", "5 seconds"),
        ("alephium.broker.broker-num", 1)
      )

    val blocks0 = for {
      from <- 0 until groups0
      to   <- 0 until groups0
    } yield emptyBlock(blockFlow, ChainIndex.unsafe(from, to))
    blocks0.foreach(addAndCheck(blockFlow, _, 1))

    val blocks1 = for {
      from <- 0 until groups0
      to   <- 0 until groups0
    } yield emptyBlock(blockFlow, ChainIndex.unsafe(from, to))
    blocks1.foreach(addAndCheck(blockFlow, _, 2))
    blocks0.zip(blocks1).foreach { case (block0, block1) =>
      val chainIndex = block0.chainIndex
      block1.blockDeps.inDeps is block0.blockDeps.inDeps
      block1.blockDeps.outDeps(chainIndex.to.value) is block0.hash
      block1.blockDeps.outDeps.take(chainIndex.to.value) is
        block0.blockDeps.outDeps.take(chainIndex.to.value)
      block1.blockDeps.outDeps.drop(chainIndex.to.value + 1) is
        block0.blockDeps.outDeps.drop(chainIndex.to.value + 1)
    }
  }

  it should "include new block as dependency when gap time is past" in new FlowFixture {
    override val configValues =
      Map(
        ("alephium.consensus.uncle-dependency-gap-time", "5 seconds"),
        ("alephium.broker.broker-num", 1)
      )

    val blocks0 = for {
      from <- 0 until groups0
      to   <- 0 until groups0
    } yield emptyBlock(blockFlow, ChainIndex.unsafe(from, to))
    blocks0.foreach(addAndCheck(blockFlow, _, 1))

    Thread.sleep(5000)

    val blocks1 = for {
      from <- 0 until groups0
      to   <- 0 until groups0
    } yield emptyBlock(blockFlow, ChainIndex.unsafe(from, to))
    blocks1.foreach(addAndCheck(blockFlow, _, brokerConfig.depsNum + 1))
  }

  it should "support sequential transactions" in new FlowFixture with Generators {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    forAll(groupIndexGen, groupIndexGen, groupIndexGen) { case (fromGroup, toGroup0, toGroup1) =>
      val block0 = transfer(blockFlow, ChainIndex(fromGroup, toGroup0))
      addAndCheck(blockFlow, block0)
      val block1 = transfer(blockFlow, ChainIndex(fromGroup, toGroup1))
      addAndCheck(blockFlow, block1)
    }
  }

  def checkInBestDeps(groupIndex: GroupIndex, blockFlow: BlockFlow, block: Block): Assertion = {
    blockFlow.getBestDeps(groupIndex).deps.contains(block.hash) is true
  }

  def checkInBestDeps(
      groupIndex: GroupIndex,
      blockFlow: BlockFlow,
      blocks: IndexedSeq[Block]
  ): Assertion = {
    val bestDeps = blockFlow.getBestDeps(groupIndex).deps
    blocks.exists { block =>
      bestDeps.contains(block.hash)
    } is true
  }
}
