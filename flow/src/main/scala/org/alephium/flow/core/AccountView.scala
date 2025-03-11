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

import scala.annotation.tailrec
import scala.collection.mutable

import org.alephium.flow.core.BlockFlowState.BlockCache
import org.alephium.io.{IOResult, IOUtils}
import org.alephium.protocol.model._
import org.alephium.util.AVector

final case class AccountView(
    checkpoint: Block,
    inBlocks: AVector[Block],
    outBlocks: AVector[Block]
) {
  private val mainGroup: GroupIndex = checkpoint.chainIndex.from
  private var cache: Option[(AVector[BlockCache], AVector[Transaction])] = None

  def getBlockCachesAndConflictedTxs(
      blockFlowState: BlockFlowState
  ): IOResult[(AVector[BlockCache], AVector[Transaction])] = {
    cache match {
      case Some(value) => Right(value)
      case None =>
        IOUtils.tryExecute(calcBlockCachesAndConflictedTxsUnsafe(blockFlowState)).map { result =>
          cache = Some(result)
          result
        }
    }
  }

  private def calcBlockCachesAndConflictedTxsUnsafe(blockFlowState: BlockFlowState) = {
    val allBlocks = inBlocks ++ outBlocks
    val filteredBlocks = AVector
      .from(allBlocks.groupBy(_.chainIndex).flatMap(v => getMainChainBlocks(blockFlowState, v._2)))
      .sortBy(_.timestamp)
    val blockCaches   = mutable.ArrayBuffer.empty[BlockFlowState.BlockCache]
    val usedInputs    = mutable.Set.empty[TxOutputRef]
    val conflictedTxs = mutable.ArrayBuffer.empty[Transaction]
    filteredBlocks.foreach { block =>
      blockCaches.addOne(blockFlowState.getBlockCacheUnsafe(mainGroup, block))
      if (block.chainIndex.from == mainGroup) {
        block.nonCoinbase.foreach { tx =>
          if (tx.allInputRefs.exists(usedInputs.contains)) conflictedTxs.addOne(tx)
          usedInputs.addAll(tx.allInputRefs)
        }
      }
    }
    (AVector.from(blockCaches), AVector.from(conflictedTxs))
  }

  @inline private def getMainChainBlocks(
      blockFlowState: BlockFlowState,
      blocks: AVector[Block]
  ): AVector[Block] = {
    assume(blocks.nonEmpty)
    if (blocks.length == 1) {
      blocks
    } else {
      val chain  = blockFlowState.getBlockChain(blocks.head.chainIndex)
      val sorted = blocks.sortBy(_.hash)(chain.blockHashOrdering.reverse)
      val acc    = AVector.ofCapacity[Block](sorted.length) :+ sorted.head
      getMainChainBlocks(sorted.head, sorted.tail, acc)
    }
  }

  @tailrec private def getMainChainBlocks(
      tip: Block,
      remains: AVector[Block],
      acc: AVector[Block]
  ): AVector[Block] = {
    if (remains.isEmpty) {
      acc
    } else {
      val block = remains.head
      if (block.hash == tip.parentHash) {
        getMainChainBlocks(block, remains.tail, acc :+ block)
      } else {
        getMainChainBlocks(tip, remains.tail, acc)
      }
    }
  }

  private def tryAddInBlockUnsafe(blockFlow: BlockFlow, block: Block): Option[AccountView] = {
    val inChainIndex = block.chainIndex
    assume(!inChainIndex.isIntraGroup && inChainIndex.to == mainGroup)
    val groupTip = blockFlow.getGroupTip(checkpoint.header, inChainIndex.from)
    val inTip    = blockFlow.getInTipUnsafe(groupTip, inChainIndex.to)
    if (blockFlow.isExtendingUnsafe(block.hash, inTip)) {
      Some(copy(inBlocks = inBlocks :+ block))
    } else {
      None
    }
  }

  def tryAddInBlock(blockFlow: BlockFlow, block: Block): IOResult[Option[AccountView]] = {
    IOUtils.tryExecute(tryAddInBlockUnsafe(blockFlow, block))
  }

  private def tryAddOutBlockUnsafe(blockFlow: BlockFlow, block: Block): Option[AccountView] = {
    val outChainIndex = block.chainIndex
    assume(!outChainIndex.isIntraGroup && outChainIndex.from == mainGroup)
    val outDep = blockFlow.getOutTip(checkpoint.header, outChainIndex.to)
    if (blockFlow.isExtendingUnsafe(block.hash, outDep)) {
      Some(copy(outBlocks = outBlocks :+ block))
    } else {
      None
    }
  }

  def tryAddOutBlock(blockFlow: BlockFlow, block: Block): IOResult[Option[AccountView]] = {
    IOUtils.tryExecute(tryAddOutBlockUnsafe(blockFlow, block))
  }
}

object AccountView {
  private def getInBlocksUnsafe(
      blockFlow: BlockFlow,
      checkpointBlock: Block
  ): AVector[Block] = {
    val mainGroup = checkpointBlock.chainIndex.from
    checkpointBlock.blockDeps.inDeps.flatMap { inDep =>
      val inTip        = blockFlow.getInTipUnsafe(inDep, mainGroup)
      val inChainIndex = ChainIndex.from(inTip)(blockFlow.brokerConfig)
      val blockchain   = blockFlow.getBlockChain(inChainIndex)
      val fromBlock    = blockchain.getBlockUnsafe(inTip)
      blockchain.tryGetBlocksFromUnsafe(fromBlock)
    }
  }

  private def getOutBlocksUnsafe(
      blockFlow: BlockFlow,
      checkpointBlock: Block
  ): AVector[Block] = {
    checkpointBlock.blockDeps.outDeps.flatMap { outDep =>
      val depChainIndex = ChainIndex.from(outDep)(blockFlow.brokerConfig)
      if (depChainIndex == checkpointBlock.chainIndex) {
        AVector.empty
      } else {
        val blockchain = blockFlow.getBlockChain(depChainIndex)
        val fromBlock  = blockchain.getBlockUnsafe(outDep)
        blockchain.tryGetBlocksFromUnsafe(fromBlock)
      }
    }
  }

  def unsafe(blockFlow: BlockFlow, checkpointBlock: Block): AccountView = {
    val chainIndex = checkpointBlock.chainIndex
    assume(chainIndex.isIntraGroup && blockFlow.brokerConfig.contains(chainIndex.from))
    if (checkpointBlock.isGenesis) {
      AccountView(checkpointBlock, AVector.empty, AVector.empty)
    } else {
      val inBlocks  = getInBlocksUnsafe(blockFlow, checkpointBlock)
      val outBlocks = getOutBlocksUnsafe(blockFlow, checkpointBlock)
      AccountView(checkpointBlock, inBlocks, outBlocks)
    }
  }

  def from(blockFlow: BlockFlow, checkpointBlock: Block): IOResult[AccountView] = {
    IOUtils.tryExecute(unsafe(blockFlow, checkpointBlock))
  }
}
