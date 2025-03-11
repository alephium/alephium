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

import org.alephium.io.{IOResult, IOUtils}
import org.alephium.protocol.model._
import org.alephium.util.AVector

final case class AccountView(
    checkpoint: Block,
    inBlocks: AVector[Block],
    outBlocks: AVector[Block]
) {
  private val mainGroup: GroupIndex = checkpoint.chainIndex.from

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
