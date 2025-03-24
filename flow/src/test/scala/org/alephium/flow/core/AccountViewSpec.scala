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

import scala.collection.mutable

import org.alephium.flow.FlowFixture
import org.alephium.protocol.model._
import org.alephium.util.{AlephiumSpec, AVector}

class AccountViewSpec extends AlephiumSpec {
  trait Fixture extends FlowFixture {
    override val configValues: Map[String, Any] = Map(
      ("alephium.broker.groups", 4),
      ("alephium.broker.broker-num", 1)
    )
    setHardForkSince(HardFork.Danube)

    lazy val mainGroup   = GroupIndex.random
    lazy val otherGroups = brokerConfig.groupRange.filter(_ != mainGroup.value)

    def mineBlockInForkChain(chainIndex: ChainIndex, parentHeight: Int): Block = {
      val hashes = blockFlow.getHashes(chainIndex, parentHeight).rightValue
      hashes.length > 1 is true
      val parentBlock     = blockFlow.getBlockUnsafe(hashes.last)
      val parentHashIndex = brokerConfig.groups - 1 + chainIndex.to.value
      val deps            = parentBlock.blockDeps.deps.replace(parentHashIndex, hashes.last)
      val block           = mine(blockFlow, chainIndex, BlockDeps.unsafe(deps))
      addAndCheck(blockFlow, block)
      block
    }
  }

  it should "create account view from genesis blocks" in new Fixture {
    brokerConfig.groupRange.foreach { index =>
      val mainGroup    = GroupIndex.unsafe(index)
      val genesisBlock = blockFlow.genesisBlocks(index)(index)
      val inTips = brokerConfig.cliqueGroups.filter(_ != mainGroup).map { from =>
        blockFlow.genesisHashes(from.value)(mainGroup.value)
      }
      AccountView.from(blockFlow, genesisBlock).rightValue is
        AccountView(genesisBlock, inTips, AVector.empty, AVector.empty)
    }
  }

  it should "create account view from non-genesis blocks" in new Fixture {
    def mineInterBlocks(chainIndex: ChainIndex): AVector[Block] = {
      val block0 = emptyBlock(blockFlow, chainIndex)
      val block1 = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, block0, block1)
      val block2 = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, block2)
      AVector(block0, block1, block2)
    }

    val block0 = emptyBlock(blockFlow, ChainIndex(mainGroup, mainGroup))
    addAndCheck(blockFlow, block0)
    val inTips0 = brokerConfig.cliqueGroups.filter(_ != mainGroup).map { from =>
      blockFlow.genesisHashes(from.value)(mainGroup.value)
    }
    AccountView.from(blockFlow, block0).rightValue is
      AccountView(block0, inTips0, AVector.empty, AVector.empty)

    val inBlocksPerChain = otherGroups.map { index =>
      mineInterBlocks(ChainIndex.unsafe(index, mainGroup.value))
    }
    val inBlocks     = inBlocksPerChain.flatten
    val accountView0 = AccountView.from(blockFlow, block0).rightValue
    accountView0.checkpoint is block0
    accountView0.inTips is inTips0
    accountView0.inBlocks.toSet is inBlocks.toSet
    accountView0.outBlocks.isEmpty is true

    val outBlocks = otherGroups.flatMap { index =>
      mineInterBlocks(ChainIndex.unsafe(mainGroup.value, index))
    }
    val accountView1 = AccountView.from(blockFlow, block0).rightValue
    accountView1.checkpoint is block0
    accountView1.inTips is inTips0
    accountView1.inBlocks.toSet is inBlocks.toSet
    accountView1.outBlocks.toSet is outBlocks.toSet

    otherGroups.foreach { groupIndex =>
      val chainIndex = ChainIndex.unsafe(groupIndex, groupIndex)
      addAndCheck(blockFlow, emptyBlock(blockFlow, chainIndex))
    }

    val block1 = emptyBlock(blockFlow, ChainIndex(mainGroup, mainGroup))
    addAndCheck(blockFlow, block1)
    val accountView2 = AccountView.from(blockFlow, block1).rightValue
    val inTips1      = AVector.from(inBlocksPerChain.map(_.last.hash))
    accountView2.checkpoint is block1
    accountView2.inTips is inTips1
    accountView2.inTips isnot inTips0
    accountView2.inBlocks.isEmpty is true
    accountView2.outBlocks.isEmpty is true
  }

  it should "add incoming blocks to account view" in new Fixture {
    otherGroups.foreach { from =>
      val chainIndex = ChainIndex.unsafe(from, mainGroup.value)
      val block0     = emptyBlock(blockFlow, chainIndex)
      val block1     = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, block0, block1)
      val block2 = emptyBlock(blockFlow, ChainIndex.unsafe(from, from))
      addAndCheck(blockFlow, block2)
    }

    val checkpoint = emptyBlock(blockFlow, ChainIndex(mainGroup, mainGroup))
    addAndCheck(blockFlow, checkpoint)
    var accountView = AccountView.from(blockFlow, checkpoint).rightValue
    accountView.checkpoint is checkpoint
    accountView.inBlocks.isEmpty is true
    accountView.outBlocks.isEmpty is true

    def addAndCheckView(block: Block) = {
      val newAccountView = accountView.tryAddInBlock(blockFlow, block).rightValue.get
      newAccountView.inBlocks.length is accountView.inBlocks.length + 1
      newAccountView.inBlocks.contains(block) is true
      accountView = newAccountView
    }

    otherGroups.foreach { from =>
      val chainIndex = ChainIndex.unsafe(from, mainGroup.value)
      val block0     = emptyBlock(blockFlow, chainIndex)
      val block1     = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, block0, block1)
      addAndCheckView(block0)
      addAndCheckView(block1)
      val block2 = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, block2)
      addAndCheckView(block2)

      val block3 = mineBlockInForkChain(chainIndex, 1)
      accountView.tryAddInBlock(blockFlow, block3).rightValue.isEmpty is true
    }
  }

  it should "add outgoing blocks to account view" in new Fixture {
    otherGroups.foreach { to =>
      val chainIndex = ChainIndex.unsafe(mainGroup.value, to)
      val block0     = emptyBlock(blockFlow, chainIndex)
      val block1     = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, block0, block1)
    }

    val checkpoint = emptyBlock(blockFlow, ChainIndex(mainGroup, mainGroup))
    addAndCheck(blockFlow, checkpoint)
    var accountView = AccountView.from(blockFlow, checkpoint).rightValue
    accountView.checkpoint is checkpoint
    accountView.inBlocks.isEmpty is true
    accountView.outBlocks.isEmpty is true

    def addAndCheckView(block: Block) = {
      val newAccountView = accountView.tryAddOutBlock(blockFlow, block).rightValue.get
      newAccountView.outBlocks.length is accountView.outBlocks.length + 1
      newAccountView.outBlocks.contains(block) is true
      accountView = newAccountView
    }

    otherGroups.foreach { to =>
      val chainIndex = ChainIndex.unsafe(mainGroup.value, to)
      val block0     = emptyBlock(blockFlow, chainIndex)
      val block1     = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, block0, block1)
      addAndCheckView(block0)
      addAndCheckView(block1)
      val block2 = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, block2)
      addAndCheckView(block2)

      val block3 = mineBlockInForkChain(chainIndex, 1)
      accountView.tryAddOutBlock(blockFlow, block3).rightValue.isEmpty is true
    }
  }

  it should "calculate block caches and conflicted txs" in new Fixture {
    otherGroups.foreach { from =>
      val block0 = emptyBlock(blockFlow, ChainIndex.unsafe(from, mainGroup.value))
      addAndCheck(blockFlow, block0)
      val block1 = emptyBlock(blockFlow, ChainIndex.unsafe(from, from))
      addAndCheck(blockFlow, block1)
      val block2 = emptyBlock(blockFlow, ChainIndex.unsafe(mainGroup.value, from))
      addAndCheck(blockFlow, block2)
    }

    val checkpoint  = emptyBlock(blockFlow, ChainIndex(mainGroup, mainGroup))
    var accountView = AccountView.from(blockFlow, checkpoint).rightValue
    def addToView(block: Block): Unit = {
      val newView = if (block.chainIndex.to == mainGroup) {
        accountView.tryAddInBlock(blockFlow, block)
      } else {
        accountView.tryAddOutBlock(blockFlow, block)
      }
      accountView = newView.rightValue.get
    }

    val blocks = otherGroups.flatMap { from =>
      val chainIndex0 = ChainIndex.unsafe(from, mainGroup.value)
      val block0      = transfer(blockFlow, chainIndex0)
      val block1      = transfer(blockFlow, chainIndex0)
      val chainIndex1 = ChainIndex.unsafe(mainGroup.value, from)
      val block2      = transfer(blockFlow, chainIndex1)
      val block3      = transfer(blockFlow, chainIndex1)
      AVector(block0, block1, block2, block3)
    }
    addAndCheck(blockFlow, blocks: _*)
    blocks.foreach(addToView)

    val mainchainBlocks = mutable.ArrayBuffer.empty[Block]
    otherGroups.foreach { from =>
      val chainIndex0 = ChainIndex.unsafe(from, mainGroup.value)
      val block0      = emptyBlock(blockFlow, chainIndex0)
      val chainIndex1 = ChainIndex.unsafe(mainGroup.value, from)
      val block1      = emptyBlock(blockFlow, chainIndex1)
      addAndCheck(blockFlow, block0, block1)
      addToView(block0)
      addToView(block1)
      mainchainBlocks.addAll(
        Seq(
          blockFlow.getBlockUnsafe(block0.parentHash),
          block0,
          blockFlow.getBlockUnsafe(block1.parentHash),
          block1
        )
      )
    }

    val (blockCaches0, conflictedTxs0) =
      accountView.getBlockCachesAndConflictedTxs(blockFlow).rightValue
    blockCaches0.toSet is mainchainBlocks.map(blockFlow.getBlockCacheUnsafe(mainGroup, _)).toSet

    conflictedTxs0 is AVector
      .from(otherGroups)
      .map { to =>
        val chainIndex = ChainIndex.unsafe(mainGroup.value, to)
        val hash       = blockFlow.getHashes(chainIndex, 2).rightValue.head
        blockFlow.getBlockUnsafe(hash)
      }
      .sortBy(_.timestamp)
      .tail
      .map(_.nonCoinbase.head)
  }
}
