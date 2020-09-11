package org.alephium.flow.core

import scala.annotation.tailrec

import org.alephium.io.{IOResult, IOUtils}
import org.alephium.protocol.Hash
import org.alephium.protocol.model.{Block, BlockHeader}
import org.alephium.util.AVector

trait BlockFlowValidation extends ConflictedBlocks with FlowTipsUtil { self: BlockFlow =>
  def getBlockUnsafe(hash: Hash): Block

  def checkFlowBlock(block: Block): IOResult[Boolean] =
    IOUtils.tryExecute(checkFlowUnsafe(block))

  def checkFlowHeader(header: BlockHeader): IOResult[Boolean] =
    IOUtils.tryExecute(checkFlowUnsafe(header))

  def checkFlowUnsafe(block: Block): Boolean = {
    assume(!block.isGenesis)

    checkFlowDepsUnsafe(block.header, checkTxConflicts = true) && checkFlowTxsUnsafe(block)
  }

  def checkFlowUnsafe(header: BlockHeader): Boolean = {
    assume(!header.isGenesis)

    checkFlowDepsUnsafe(header, checkTxConflicts = false)
  }

  def checkFlowDepsUnsafe(header: BlockHeader, checkTxConflicts: Boolean): Boolean = {
    val targetGroup = header.chainIndex.from
    assume(!(checkTxConflicts ^ brokerConfig.contains(targetGroup)))

    val blockDeps   = header.blockDeps
    val initialTips = getFlowTipsUnsafe(blockDeps.head, targetGroup)

    @tailrec
    def iter(currentTips: FlowTips, tips: AVector[Hash]): Option[FlowTips] = {
      if (tips.isEmpty) Some(currentTips)
      else {
        tryMergeUnsafe(currentTips, tips.head, targetGroup, checkTxConflicts) match {
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
