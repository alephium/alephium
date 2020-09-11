package org.alephium.flow.core

import scala.annotation.tailrec

import org.alephium.io.{IOResult, IOUtils}
import org.alephium.protocol.Hash
import org.alephium.protocol.model.Block
import org.alephium.util.AVector

trait BlockFlowValidation extends ConflictedBlocks with FlowTipsUtil { self: BlockFlow =>
  def getBlockUnsafe(hash: Hash): Block

  def checkFlow(block: Block): IOResult[Boolean] = IOUtils.tryExecute(checkFlowUnsafe(block))

  def checkFlowUnsafe(block: Block): Boolean = {
    assume(!block.isGenesis)

    checkFlowDepsUnsafe(block) && checkFlowTxsUnsafe(block)
  }

  def checkFlowDepsUnsafe(block: Block): Boolean = {
    val targetGroup = block.chainIndex.from
    assume(brokerConfig.contains(targetGroup))

    val blockDeps   = block.header.blockDeps
    val initialTips = getFlowTipsUnsafe(blockDeps.head, targetGroup)

    @tailrec
    def iter(currentTips: FlowTips, tips: AVector[Hash]): Option[FlowTips] = {
      if (tips.isEmpty) Some(currentTips)
      else {
        tryMergeUnsafe(currentTips, tips.head, targetGroup) match {
          case Some(merged) => iter(merged, tips.tail)
          case None         => None
        }
      }
    }

    iter(initialTips, blockDeps.tail).exists(_.toBlockDeps.deps == blockDeps)
  }

  def checkFlowTxsUnsafe(block: Block): Boolean = {
    val newOutTips = block.header.outDeps
    val intraDep   = block.header.intraDep
    val oldOutTips = getOutTips(getBlockHeaderUnsafe(intraDep), inclusive = true)
    val diff       = getTipsDiffUnsafe(newOutTips, oldOutTips)
    cacheForConflicts(block)
    !isConflicted(block.hash, diff, getBlockUnsafe)
  }
}
