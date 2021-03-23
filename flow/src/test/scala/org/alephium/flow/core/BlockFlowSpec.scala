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

import org.scalacheck.Gen
import org.scalatest.Assertion

import org.alephium.flow.FlowFixture
import org.alephium.flow.core.BlockChain.TxIndex
import org.alephium.flow.core.BlockFlowState.TxStatus
import org.alephium.flow.io.StoragesFixture
import org.alephium.flow.setting.AlephiumConfigFixture
import org.alephium.protocol.{ALF, BlockHash}
import org.alephium.protocol.config.GroupConfigFixture
import org.alephium.protocol.model._
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{AlephiumSpec, AVector, Random, TimeStamp}

class BlockFlowSpec extends AlephiumSpec {
  it should "compute correct blockflow height" in new FlowFixture {
    config.genesisBlocks.flatMap(identity).foreach { block =>
      blockFlow.getWeight(block.hash) isE 0
    }

    checkBalance(blockFlow, brokerConfig.groupFrom, genesisBalance)
  }

  it should "work for at least 2 user group when adding blocks sequentially" in new FlowFixture {
    if (brokerConfig.groups >= 2) {
      val chainIndex1 = ChainIndex.unsafe(0, 0)
      val block1      = transfer(blockFlow, chainIndex1)
      addAndCheck(blockFlow, block1, 1)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block1)
      checkBalance(blockFlow, 0, genesisBalance - ALF.alf(1))

      val chainIndex2 = ChainIndex.unsafe(1, 1)
      val block2      = emptyBlock(blockFlow, chainIndex2)
      addAndCheck(blockFlow, block2.header, 2)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block2)
      checkBalance(blockFlow, 0, genesisBalance - ALF.alf(1))

      val chainIndex3 = ChainIndex.unsafe(0, 1)
      val block3      = transfer(blockFlow, chainIndex3)
      addAndCheck(blockFlow, block3, 3)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block3)
      checkBalance(blockFlow, 0, genesisBalance - ALF.alf(1))

      val chainIndex4 = ChainIndex.unsafe(0, 0)
      val block4      = emptyBlock(blockFlow, chainIndex4)
      addAndCheck(blockFlow, block4, 4)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block4)
      checkBalance(blockFlow, 0, genesisBalance - ALF.alf(2))

      val chainIndex5 = ChainIndex.unsafe(0, 0)
      val block5      = transfer(blockFlow, chainIndex5)
      addAndCheck(blockFlow, block5, 5)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block5)
      checkBalance(blockFlow, 0, genesisBalance - ALF.alf(3))
    }
  }

  it should "set proper initial bestDeps" in new FlowFixture {
    val validation = blockFlow.bestDeps.zipWithIndex.forall { case (bestDep, fromShift) =>
      val mainGroup = GroupIndex.unsafe(brokerConfig.groupFrom + fromShift)
      blockFlow.checkFlowDepsUnsafe(bestDep, mainGroup)
    }
    validation is true
  }

  it should "compute cached blocks" in new FlowFixture {
    val newBlocks = for {
      i <- 0 to 1
      j <- 0 to 1
    } yield transferOnlyForIntraGroup(blockFlow, ChainIndex.unsafe(i, j))
    newBlocks.foreach { block =>
      val index = block.chainIndex
      if (index.relateTo(GroupIndex.unsafe(0))) {
        addAndCheck(blockFlow, block, 1)
      } else {
        addAndCheck(blockFlow, block.header, 1)
      }
    }

    val cache0 = blockFlow.getHashesForUpdates(GroupIndex.unsafe(0)).toOption.get
    val expected =
      if (blockFlow.blockHashOrdering.lt(newBlocks(2).hash, newBlocks(3).hash)) 1 else 2
    cache0.length is expected
    cache0.contains(newBlocks(0).hash) is false
    cache0.contains(newBlocks(1).hash) is true
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
        blockFlow.getWeight(block) isE consensusConfig.maxMiningTarget * 1
      }
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, newBlocks1)
      checkBalance(blockFlow, 0, genesisBalance - ALF.alf(1))
      newBlocks1.map(_.hash).contains(blockFlow.getBestTipUnsafe) is true

      val newBlocks2 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield transferOnlyForIntraGroup(blockFlow, ChainIndex.unsafe(i, j))
      newBlocks2.foreach { block => addAndCheck(blockFlow, block, 4) }
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, newBlocks2)
      checkBalance(blockFlow, 0, genesisBalance - ALF.alf(2))
      newBlocks2.map(_.hash).contains(blockFlow.getBestTipUnsafe) is true

      val newBlocks3 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield transferOnlyForIntraGroup(blockFlow, ChainIndex.unsafe(i, j))
      newBlocks3.foreach { block => addAndCheck(blockFlow, block, 8) }
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, newBlocks3)
      checkBalance(blockFlow, 0, genesisBalance - ALF.alf(3))
      newBlocks3.map(_.hash).contains(blockFlow.getBestTipUnsafe) is true
    }
  }

  it should "work for 2 user group when there is a fork" in new FlowFixture {
    if (brokerConfig.groups >= 2) {
      val chainIndex1 = ChainIndex.unsafe(0, 0)
      val block11     = transfer(blockFlow, chainIndex1)
      val block12     = transfer(blockFlow, chainIndex1)
      addAndCheck(blockFlow, block11, 1)
      addAndCheck(blockFlow, block12, 1)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, IndexedSeq(block11, block12))
      checkBalance(blockFlow, 0, genesisBalance - ALF.alf(1))

      val block13 = transfer(blockFlow, chainIndex1)
      addAndCheck(blockFlow, block13, 2)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block13)
      checkBalance(blockFlow, 0, genesisBalance - ALF.alf(2))

      val chainIndex2 = ChainIndex.unsafe(1, 1)
      val block21     = emptyBlock(blockFlow, chainIndex2)
      val block22     = emptyBlock(blockFlow, chainIndex2)
      addAndCheck(blockFlow, block21.header, 3)
      addAndCheck(blockFlow, block22.header, 3)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, IndexedSeq(block21, block22))
      checkBalance(blockFlow, 0, genesisBalance - ALF.alf(2))

      val chainIndex3 = ChainIndex.unsafe(0, 1)
      val block3      = transfer(blockFlow, chainIndex3)
      addAndCheck(blockFlow, block3, 4)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block3)
      checkBalance(blockFlow, 0, genesisBalance - ALF.alf(2))
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

  it should "update mempool correctly" in new FlowFixture {
    if (brokerConfig.groups >= 2) {
      forAll(Gen.choose(brokerConfig.groupFrom, brokerConfig.groupUntil - 1)) { mainGroup =>
        val blockFlow = genesisBlockFlow()

        val chainIndex = ChainIndex.unsafe(mainGroup, 0)
        val block11    = tryToTransfer(blockFlow, chainIndex)
        val block12    = tryToTransfer(blockFlow, chainIndex)
        blockFlow.mempools.foreach(_.size is 0)
        addAndCheck(blockFlow, block11, 1)
        blockFlow.mempools.foreach(_.size is 0)
        addAndCheck(blockFlow, block12, 1)

        val blockAdded = blockFlow.getBestDeps(chainIndex.from).getOutDep(chainIndex.to)
        if (blockAdded equals block12.hash) {
          blockFlow.getPool(chainIndex).size is 1 // the conflicted tx is kept
          val template = blockFlow.prepareBlockFlow(chainIndex).toOption.get
          template.transactions.length is 0 // the conflicted tx will not be used
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
    checkBalance(blockFlow1, 0, genesisBalance - ALF.alf(2))
    newBlocks2.map(_.hash).contains(blockFlow1.getBestTipUnsafe) is true
  }

  behavior of "Sync"

  it should "compute sync locators and inventories" in new FlowFixture {
    brokerConfig.groupNumPerBroker is 1 // the test only works in this case

    (0 until brokerConfig.groups).foreach { testToGroup =>
      val blockFlow0    = isolatedBlockFlow()
      val testFromGroup = brokerConfig.groupFrom
      val blocks = (1 to 6).map { k =>
        val block =
          transferOnlyForIntraGroup(blockFlow0, ChainIndex.unsafe(testFromGroup, testToGroup))
        addAndCheck(blockFlow0, block, k)
        block
      }
      val hashes0 = AVector.from(blocks.map(_.hash))
      val locators0: AVector[AVector[BlockHash]] =
        AVector.tabulate(groupConfig.groups) { group =>
          if (group equals testToGroup) {
            AVector(
              config.genesisBlocks(testFromGroup)(testToGroup).hash,
              hashes0(0),
              hashes0(1),
              hashes0(3),
              hashes0(4),
              hashes0(5)
            )
          } else {
            AVector(config.genesisBlocks(testFromGroup)(group).hash)
          }
        }
      blockFlow0.getSyncLocators() isE locators0

      val blockFlow1 = isolatedBlockFlow()
      val locators1: AVector[AVector[BlockHash]] = AVector.tabulate(config.broker.groups) { group =>
        AVector(config.genesisBlocks(testFromGroup)(group).hash)
      }
      blockFlow1.getSyncLocators() isE locators1

      blockFlow0.getSyncInventories(locators0) isE
        AVector.fill(groupConfig.groups)(AVector.empty[BlockHash])
      blockFlow0.getSyncInventories(locators1) isE
        AVector.tabulate(groupConfig.groups) { group =>
          if (group equals testToGroup) hashes0 else AVector.empty[BlockHash]
        }
      blockFlow1.getSyncInventories(locators0) isE
        AVector.fill(groupConfig.groups)(AVector.empty[BlockHash])
      blockFlow1.getSyncInventories(locators1) isE
        AVector.fill(groupConfig.groups)(AVector.empty[BlockHash])

      (0 until brokerConfig.brokerNum).foreach { id =>
        val remoteBrokerInfo = new BrokerGroupInfo {
          override def brokerId: Int          = id
          override def groupNumPerBroker: Int = brokerConfig.groupNumPerBroker
        }
        blockFlow0.getIntraSyncInventories(remoteBrokerInfo) isE
          (if (remoteBrokerInfo.groupFrom equals testToGroup) {
             AVector(hashes0)
           } else {
             AVector(AVector.empty[BlockHash])
           })
        blockFlow1.getIntraSyncInventories(remoteBrokerInfo) isE AVector(AVector.empty[BlockHash])
      }

      val remoteBrokerInfo = new BrokerGroupInfo {
        override def brokerId: Int          = 0
        override def groupNumPerBroker: Int = brokerConfig.groups
      }
      blockFlow0.getIntraSyncInventories(remoteBrokerInfo) isE
        AVector.tabulate(brokerConfig.groups) { k =>
          if (k equals testToGroup) hashes0 else AVector.empty[BlockHash]
        }
      blockFlow1.getIntraSyncInventories(remoteBrokerInfo) isE
        AVector.fill(brokerConfig.groups)(AVector.empty[BlockHash])
    }
  }

  behavior of "Mining"

  it should "sanity check rewards" in new FlowFixture {
    val block = transferOnlyForIntraGroup(blockFlow, ChainIndex.unsafe(0, 0))
    block.nonCoinbase.nonEmpty is true
    val minimalReward = Seq(
      consensusConfig.emission.lowHashRateInitialRewardPerChain,
      consensusConfig.emission.stableMaxRewardPerChain
    ).min
    (block.coinbase.alfAmountInOutputs.get > minimalReward) is true
  }

  behavior of "Balance"

  it should "transfer token inside a same group" in new FlowFixture {
    val testGroup = Random.source.nextInt(brokerConfig.groupNumPerBroker) + brokerConfig.groupFrom
    val block     = transferOnlyForIntraGroup(blockFlow, ChainIndex.unsafe(testGroup, testGroup))
    block.nonCoinbase.nonEmpty is true
    addAndCheck(blockFlow, block, 1)

    val pubScript = block.nonCoinbase.head.unsigned.fixedOutputs.head.lockupScript
    checkBalance(blockFlow, pubScript, ALF.alf(1) - defaultGasFee)
    checkBalance(blockFlow, testGroup, genesisBalance - ALF.alf(1))
  }

  it should "transfer token for inter-group transactions" in new FlowFixture { Test =>
    val anotherBroker = (brokerConfig.brokerId + 1 + Random.source.nextInt(
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

    val fromGroup = Random.source.nextInt(brokerConfig.groupNumPerBroker) + brokerConfig.groupFrom
    val toGroup =
      Random.source.nextInt(brokerConfig.groupNumPerBroker) + anotherConfig.broker.groupFrom

    val block = transfer(blockFlow0, ChainIndex.unsafe(fromGroup, toGroup))
    block.nonCoinbase.nonEmpty is true
    addAndCheck(blockFlow0, block, 1)
    checkBalance(blockFlow0, fromGroup, genesisBalance)
    addAndCheck(blockFlow1, block, 1)
    val pubScript = block.nonCoinbase.head.unsigned.fixedOutputs.head.lockupScript
    checkBalance(blockFlow1, pubScript, 0)

    val fromGroupBlock = emptyBlock(blockFlow0, ChainIndex.unsafe(fromGroup, fromGroup))
    addAndCheck(blockFlow0, fromGroupBlock, 2)
    addAndCheck(blockFlow1, fromGroupBlock.header, 2)
    checkBalance(blockFlow0, fromGroup, genesisBalance - ALF.alf(1))

    val toGroupBlock = emptyBlock(blockFlow1, ChainIndex.unsafe(toGroup, toGroup))
    addAndCheck(blockFlow1, toGroupBlock, 3)
    addAndCheck(blockFlow0, toGroupBlock.header, 3)
    checkBalance(blockFlow1, pubScript, ALF.alf(1) - defaultGasFee)

    fromGroup isnot toGroup
    val newBlock = emptyBlock(blockFlow0, ChainIndex.unsafe(fromGroup, toGroup))
    addAndCheck(blockFlow0, newBlock, 4)
    addAndCheck(blockFlow1, newBlock, 4)
  }

  behavior of "Utilities"

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
          .prepareUnsignedTx(publicKey, toLockupScript, lockTimeOpt, ALF.alf(1))
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

  it should "spend locked outputs" in new FlowFixture {
    val lockTime       = TimeStamp.now().plusSecondsUnsafe(2)
    val block          = transfer(blockFlow, ChainIndex.unsafe(0, 0), lockTimeOpt = Some(lockTime))
    val toLockupScript = block.nonCoinbase.head.unsigned.fixedOutputs.head.lockupScript
    val toPrivateKey   = keyManager(toLockupScript)
    addAndCheck(blockFlow, block)
    blockFlow
      .prepareUnsignedTx(toPrivateKey.publicKey, toLockupScript, None, ALF.nanoAlf(1))
      .rightValue is Left("Not enough balance")
    Thread.sleep(2000)
    blockFlow
      .prepareUnsignedTx(toPrivateKey.publicKey, toLockupScript, None, ALF.nanoAlf(1))
      .rightValue
      .isRight is true
  }

  behavior of "confirmations"

  it should "return correct confirmations for genesis txs" in new FlowFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    blockFlow.genesisBlocks.foreachWithIndex { case (blocks, from) =>
      blocks.foreachWithIndex { case (block, to) =>
        block.transactions.foreachWithIndex { case (tx, index) =>
          from is to
          blockFlow.getTxStatus(tx.id, ChainIndex.unsafe(from, to)) isE
            Some(TxStatus(TxIndex(block.hash, index), 1, 1, 1))
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
              TxStatus(
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
            Some(TxStatus(TxIndex(block.hash, index), 2, 2, 2))
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
        Some(TxStatus(TxIndex(block.hash, 0), 1, 1, 1))
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
        Some(TxStatus(TxIndex(block0.hash, 0), 1, 0, 0))

      val block1 = emptyBlock(blockFlow0, ChainIndex.unsafe(from, from))
      addAndCheck(blockFlow0, block1)
      blockFlow0.getTxStatus(block0.transactions.head.id, chainIndex) isE
        Some(TxStatus(TxIndex(block0.hash, 0), 1, 1, 0))

      val block2 = emptyBlock(blockFlow0, ChainIndex.unsafe(to, to))
      addAndCheck(blockFlow0, block2)
      blockFlow0.getTxStatus(block0.transactions.head.id, chainIndex) isE
        Some(TxStatus(TxIndex(block0.hash, 0), 1, 1, 1))
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
