package org.alephium.flow.core

import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.core.FlowHandler.BlockFlowTemplate
import org.alephium.flow.core.mempool.{MemPool, MemPoolChanges, Normal, Reorg}
import org.alephium.flow.io.IOResult
import org.alephium.flow.model.BlockDeps
import org.alephium.protocol.model.{ChainIndex, GroupIndex, Transaction}
import org.alephium.util.AVector

trait FlowUtils extends MultiChain with BlockFlowState with StrictLogging {

  val mempools = AVector.tabulate(config.groupNumPerBroker) { idx =>
    val group = GroupIndex(brokerInfo.groupFrom + idx)
    MemPool.empty(group)
  }

  def getPool(mainGroup: Int): MemPool = {
    mempools(mainGroup - brokerInfo.groupFrom)
  }

  def getPool(chainIndex: ChainIndex): MemPool = {
    mempools(chainIndex.from.value - brokerInfo.groupFrom)
  }

  def calMemPoolChangesUnsafe(mainGroup: Int,
                              oldDeps: BlockDeps,
                              newDeps: BlockDeps): MemPoolChanges = {
    val oldOutDeps = oldDeps.outDeps
    val newOutDeps = newDeps.outDeps
    val diffs = AVector.tabulate(config.groups) { i =>
      val oldDep = oldOutDeps(i)
      val newDep = newOutDeps(i)
      val index  = ChainIndex.unsafe(mainGroup, i)
      getBlockChain(index).calBlockDiffUnsafe(newDep, oldDep)
    }
    val toRemove = diffs.map(_.toAdd.flatMap(_.transactions))
    val toAdd    = diffs.map(_.toRemove.flatMap(_.transactions.map((_, 1.0))))
    if (toRemove.isEmpty) Normal(toRemove) else Reorg(toRemove, toAdd)
  }

  def updateMemPoolUnsafe(mainGroup: Int, newDeps: BlockDeps): Unit = {
    val oldDeps = getBestDeps(GroupIndex(mainGroup))
    calMemPoolChangesUnsafe(mainGroup, oldDeps, newDeps) match {
      case Normal(toRemove) =>
        val removed = toRemove.foldWithIndex(0) { (sum, txs, toGroup) =>
          val index = ChainIndex(mainGroup, toGroup)
          sum + getPool(index).remove(index, txs)
        }
        logger.debug(s"Normal update for #$mainGroup mempool: #$removed removed")
      case Reorg(toRemove, toAdd) =>
        val (removed, added) = getPool(mainGroup).reorg(toRemove, toAdd)
        logger.debug(s"Reorg for #$mainGroup mempool: #$removed removed, #$added added")
    }
  }

  def getBestDeps(groupIndex: GroupIndex): BlockDeps

  def updateBestDeps(): IOResult[Unit]

  def calBestDepsUnsafe(): Unit

  private def collectTransactions(chainIndex: ChainIndex): AVector[Transaction] = {
    getPool(chainIndex).collectForBlock(chainIndex, config.txMaxNumberPerBlock)
  }

  def prepareBlockFlow(chainIndex: ChainIndex): IOResult[BlockFlowTemplate] = {
    assert(config.brokerInfo.contains(chainIndex.from))
    val singleChain = getBlockChain(chainIndex)
    val bestDeps    = getBestDeps(chainIndex.from)
    for {
      target <- singleChain.getHashTarget(bestDeps.getChainHash(chainIndex.to))
    } yield {
      val transactions = collectTransactions(chainIndex)
      BlockFlowTemplate(chainIndex, bestDeps.deps, target, transactions)
    }
  }

  def prepareBlockFlowUnsafe(chainIndex: ChainIndex): BlockFlowTemplate = {
    assert(config.brokerInfo.contains(chainIndex.from))
    val singleChain  = getBlockChain(chainIndex)
    val bestDeps     = getBestDeps(chainIndex.from)
    val target       = singleChain.getHashTargetUnsafe(bestDeps.getChainHash(chainIndex.to))
    val transactions = collectTransactions(chainIndex)
    BlockFlowTemplate(chainIndex, bestDeps.deps, target, transactions)
  }
}
