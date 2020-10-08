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
import org.alephium.flow.io.StoragesFixture
import org.alephium.flow.setting.AlephiumConfigFixture
import org.alephium.protocol.Hash
import org.alephium.protocol.model._
import org.alephium.util.{AlephiumSpec, AVector, Random}

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
      val block1      = mine(blockFlow, chainIndex1)
      addAndCheck(blockFlow, block1, 1)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block1)
      checkBalance(blockFlow, 0, genesisBalance - 1)

      val chainIndex2 = ChainIndex.unsafe(1, 1)
      val block2      = mine(blockFlow, chainIndex2)
      addAndCheck(blockFlow, block2.header, 2)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block2)
      checkBalance(blockFlow, 0, genesisBalance - 1)

      val chainIndex3 = ChainIndex.unsafe(0, 1)
      val block3      = mine(blockFlow, chainIndex3)
      addAndCheck(blockFlow, block3, 3)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block3)
      checkBalance(blockFlow, 0, genesisBalance - 1)

      val chainIndex4 = ChainIndex.unsafe(0, 0)
      val block4      = mine(blockFlow, chainIndex4, transfer = false)
      addAndCheck(blockFlow, block4, 4)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block4)
      checkBalance(blockFlow, 0, genesisBalance - 2)

      val chainIndex5 = ChainIndex.unsafe(0, 0)
      val block5      = mine(blockFlow, chainIndex5)
      addAndCheck(blockFlow, block5, 5)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block5)
      checkBalance(blockFlow, 0, genesisBalance - 3)
    }
  }

  it should "compute cached blocks" in new FlowFixture {
    val newBlocks = for {
      i <- 0 to 1
      j <- 0 to 1
    } yield mine(blockFlow, ChainIndex.unsafe(i, j), onlyTxForIntra = true)
    newBlocks.foreach { block =>
      val index = block.chainIndex
      if (index.relateTo(GroupIndex.unsafe(0))) {
        addAndCheck(blockFlow, block, 1)
      } else {
        addAndCheck(blockFlow, block.header, 1)
      }
    }

    val cache0 = blockFlow.getHashesForUpdates(GroupIndex.unsafe(0)).toOption.get
    cache0.length is 2
    cache0.contains(newBlocks(0).hash) is false
    cache0.contains(newBlocks(1).hash) is true
  }

  it should "work for at least 2 user group when adding blocks in parallel" in new FlowFixture {
    override val configValues = Map(
      ("alephium.broker.broker-num", 1)
    )
    if (brokerConfig.groups >= 2) {
      val blockFlow = genesisBlockFlow()

      val newBlocks1 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield mine(blockFlow, ChainIndex.unsafe(i, j), onlyTxForIntra = true)
      newBlocks1.foreach { block =>
        addAndCheck(blockFlow, block, 1)
        blockFlow.getWeight(block) isE consensusConfig.maxMiningTarget * 1
      }
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, newBlocks1)
      checkBalance(blockFlow, 0, genesisBalance - 1)
      newBlocks1.map(_.hash).contains(blockFlow.getBestTipUnsafe) is true

      val newBlocks2 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield mine(blockFlow, ChainIndex.unsafe(i, j), onlyTxForIntra = true)
      newBlocks2.foreach { block =>
        addAndCheck(blockFlow, block, 4)
        blockFlow.getChainWeight(block.hash) isE consensusConfig.maxMiningTarget * 2
      }
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, newBlocks2)
      checkBalance(blockFlow, 0, genesisBalance - 2)
      newBlocks2.map(_.hash).contains(blockFlow.getBestTipUnsafe) is true

      val newBlocks3 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield mine(blockFlow, ChainIndex.unsafe(i, j), onlyTxForIntra = true)
      newBlocks3.foreach { block =>
        addAndCheck(blockFlow, block, 8)
        blockFlow.getChainWeight(block.hash) isE consensusConfig.maxMiningTarget * 3
      }
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, newBlocks3)
      checkBalance(blockFlow, 0, genesisBalance - 3)
      newBlocks3.map(_.hash).contains(blockFlow.getBestTipUnsafe) is true
    }
  }

  it should "work for 2 user group when there is a fork" in new FlowFixture {
    if (brokerConfig.groups >= 2) {
      val chainIndex1 = ChainIndex.unsafe(0, 0)
      val block11     = mine(blockFlow, chainIndex1)
      val block12     = mine(blockFlow, chainIndex1)
      addAndCheck(blockFlow, block11, 1)
      addAndCheck(blockFlow, block12, 1)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, IndexedSeq(block11, block12))
      checkBalance(blockFlow, 0, genesisBalance - 1)

      val block13 = mine(blockFlow, chainIndex1)
      addAndCheck(blockFlow, block13, 2)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block13)
      checkBalance(blockFlow, 0, genesisBalance - 2)

      val chainIndex2 = ChainIndex.unsafe(1, 1)
      val block21     = mine(blockFlow, chainIndex2)
      val block22     = mine(blockFlow, chainIndex2)
      addAndCheck(blockFlow, block21.header, 3)
      addAndCheck(blockFlow, block22.header, 3)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, IndexedSeq(block21, block22))
      checkBalance(blockFlow, 0, genesisBalance - 2)

      val chainIndex3 = ChainIndex.unsafe(0, 1)
      val block3      = mine(blockFlow, chainIndex3)
      addAndCheck(blockFlow, block3, 4)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block3)
      checkBalance(blockFlow, 0, genesisBalance - 2)
    }
  }

  it should "update mempool correctly" in new FlowFixture {
    if (brokerConfig.groups >= 2) {
      forAll(Gen.choose(brokerConfig.groupFrom, brokerConfig.groupUntil - 1)) { mainGroup =>
        val blockFlow = genesisBlockFlow()

        val chainIndex = ChainIndex.unsafe(mainGroup, 0)
        val block11    = mine(blockFlow, chainIndex)
        val block12    = mine(blockFlow, chainIndex)
        blockFlow.mempools.foreach(_.size is 0)
        addAndCheck(blockFlow, block11, 1)
        blockFlow.mempools.foreach(_.size is 0)
        addAndCheck(blockFlow, block12, 1)

        val blockAdded = blockFlow.getBestDeps(chainIndex.from).getOutDep(chainIndex.to)
        if (blockAdded equals block12.hash) {
          blockAdded is block12.hash
          blockFlow.getPool(chainIndex).size is block11.transactions.length - 1
          val template = blockFlow.prepareBlockFlow(chainIndex).toOption.get
          template.transactions.length is block11.transactions.length - 1
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
    } yield mine(blockFlow0, ChainIndex.unsafe(i, j), onlyTxForIntra = true)
    newBlocks1.foreach { block =>
      addAndCheck(blockFlow0, block, 1)
    }
    newBlocks1.map(_.hash).diff(blockFlow0.getAllTips.toArray).isEmpty is true

    val blockFlow1 = storageBlockFlow()
    newBlocks1.map(_.hash).diff(blockFlow1.getAllTips.toArray).isEmpty is true

    val newBlocks2 = for {
      i <- 0 to 1
      j <- 0 to 1
    } yield mine(blockFlow1, ChainIndex.unsafe(i, j), onlyTxForIntra = true)
    newBlocks2.foreach { block =>
      addAndCheck(blockFlow1, block, 4)
      blockFlow1.getChainWeight(block.hash) isE consensusConfig.maxMiningTarget * 2
    }
    checkInBestDeps(GroupIndex.unsafe(0), blockFlow1, newBlocks2)
    checkBalance(blockFlow1, 0, genesisBalance - 2)
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
          mine(blockFlow0, ChainIndex.unsafe(testFromGroup, testToGroup), onlyTxForIntra = true)
        addAndCheck(blockFlow0, block, k)
        block
      }
      val hashes0 = AVector.from(blocks.map(_.hash))
      val locators0: AVector[AVector[Hash]] =
        AVector.tabulate(groupConfig.groups) { group =>
          if (group equals testToGroup)
            AVector(config.genesisBlocks(testFromGroup)(testToGroup).hash,
                    hashes0(0),
                    hashes0(1),
                    hashes0(3),
                    hashes0(4),
                    hashes0(5))
          else AVector(config.genesisBlocks(testFromGroup)(group).hash)
        }
      blockFlow0.getSyncLocators() isE locators0

      val blockFlow1 = isolatedBlockFlow()
      val locators1: AVector[AVector[Hash]] = AVector.tabulate(config.broker.groups) { group =>
        AVector(config.genesisBlocks(testFromGroup)(group).hash)
      }
      blockFlow1.getSyncLocators() isE locators1

      blockFlow0.getSyncInventories(locators0) isE
        AVector.fill(groupConfig.groups)(AVector.empty[Hash])
      blockFlow0.getSyncInventories(locators1) isE
        AVector.tabulate(groupConfig.groups) { group =>
          if (group equals testToGroup) hashes0 else AVector.empty[Hash]
        }
      blockFlow1.getSyncInventories(locators0) isE
        AVector.fill(groupConfig.groups)(AVector.empty[Hash])
      blockFlow1.getSyncInventories(locators1) isE
        AVector.fill(groupConfig.groups)(AVector.empty[Hash])

      (0 until brokerConfig.brokerNum).foreach { id =>
        val remoteBrokerInfo = new BrokerGroupInfo {
          override def brokerId: Int          = id
          override def groupNumPerBroker: Int = brokerConfig.groupNumPerBroker
        }
        blockFlow0.getIntraSyncInventories(remoteBrokerInfo) isE
          (if (remoteBrokerInfo.groupFrom equals testToGroup) AVector(hashes0)
           else AVector(AVector.empty[Hash]))
        blockFlow1.getIntraSyncInventories(remoteBrokerInfo) isE AVector(AVector.empty[Hash])
      }

      val remoteBrokerInfo = new BrokerGroupInfo {
        override def brokerId: Int          = 0
        override def groupNumPerBroker: Int = brokerConfig.groups
      }
      blockFlow0.getIntraSyncInventories(remoteBrokerInfo) isE
        AVector.tabulate(brokerConfig.groups) { k =>
          if (k equals testToGroup) hashes0 else AVector.empty[Hash]
        }
      blockFlow1.getIntraSyncInventories(remoteBrokerInfo) isE
        AVector.fill(brokerConfig.groups)(AVector.empty[Hash])
    }
  }

  behavior of "Balance"

  it should "transfer token for inside a same group" in new FlowFixture {
    val testGroup = Random.source.nextInt(brokerConfig.groupNumPerBroker) + brokerConfig.groupFrom
    val block     = mine(blockFlow, ChainIndex.unsafe(testGroup, testGroup), onlyTxForIntra = true)
    block.nonCoinbase.nonEmpty is true
    addAndCheck(blockFlow, block, 1)

    val pubScript = block.nonCoinbase.head.unsigned.fixedOutputs.head.lockupScript
    checkBalance(blockFlow, pubScript, 1)
    checkBalance(blockFlow, testGroup, genesisBalance - 1)
  }

  it should "transfer token for inter-group transactions" in new FlowFixture { Test =>
    val anotherBroker = (brokerConfig.brokerId + 1 + Random.source.nextInt(
      brokerConfig.brokerNum - 1)) % brokerConfig.brokerNum
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
    val toGroup   = Random.source.nextInt(brokerConfig.groupNumPerBroker) + anotherConfig.broker.groupFrom
    val block     = mine(blockFlow0, ChainIndex.unsafe(fromGroup, toGroup))
    block.nonCoinbase.nonEmpty is true

    addAndCheck(blockFlow0, block, 1)
    checkBalance(blockFlow0, fromGroup, genesisBalance)

    addAndCheck(blockFlow1, block, 1)
    val pubScript = block.nonCoinbase.head.unsigned.fixedOutputs.head.lockupScript
    checkBalance(blockFlow1, pubScript, 0)

    val fromGroupBlock = mine(blockFlow0, ChainIndex.unsafe(fromGroup, fromGroup), transfer = false)
    addAndCheck(blockFlow0, fromGroupBlock, 2)
    checkBalance(blockFlow0, fromGroup, genesisBalance - 1)

    val toGroupBlock = mine(blockFlow1, ChainIndex.unsafe(toGroup, toGroup), transfer = false)
    addAndCheck(blockFlow1, toGroupBlock, 2) // TODO: fix weight calculation
    checkBalance(blockFlow1, pubScript, 1)
  }

  def checkInBestDeps(groupIndex: GroupIndex, blockFlow: BlockFlow, block: Block): Assertion = {
    blockFlow.getBestDeps(groupIndex).deps.contains(block.hash) is true
  }

  def checkInBestDeps(groupIndex: GroupIndex,
                      blockFlow: BlockFlow,
                      blocks: IndexedSeq[Block]): Assertion = {
    val bestDeps = blockFlow.getBestDeps(groupIndex).deps
    blocks.exists { block =>
      bestDeps.contains(block.hash)
    } is true
  }
}
