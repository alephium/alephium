package org.alephium.flow.core

import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.Utils
import org.alephium.flow.handler.FlowHandler.BlockFlowTemplate
import org.alephium.flow.mempool.{MemPool, MemPoolChanges, Normal, Reorg}
import org.alephium.flow.model.{BlockDeps, SyncInfo}
import org.alephium.flow.setting.MemPoolSetting
import org.alephium.io.IOResult
import org.alephium.protocol.{ALF, Hash}
import org.alephium.protocol.model.{BrokerInfo, ChainIndex, GroupIndex, Transaction}
import org.alephium.util.AVector

trait FlowUtils extends MultiChain with BlockFlowState with SyncUtils with StrictLogging {
  implicit def mempoolSetting: MemPoolSetting

  val mempools = AVector.tabulate(brokerConfig.groupNumPerBroker) { idx =>
    val group = GroupIndex.unsafe(brokerConfig.groupFrom + idx)
    MemPool.empty(group)
  }

  def getPool(mainGroup: Int): MemPool = {
    mempools(mainGroup - brokerConfig.groupFrom)
  }

  def getPool(chainIndex: ChainIndex): MemPool = {
    mempools(chainIndex.from.value - brokerConfig.groupFrom)
  }

  def calMemPoolChangesUnsafe(mainGroup: Int,
                              oldDeps: BlockDeps,
                              newDeps: BlockDeps): MemPoolChanges = {
    val oldOutDeps = oldDeps.outDeps
    val newOutDeps = newDeps.outDeps
    val diffs = AVector.tabulate(brokerConfig.groups) { i =>
      val oldDep = oldOutDeps(i)
      val newDep = newOutDeps(i)
      val index  = ChainIndex.unsafe(mainGroup, i)
      getBlockChain(index).calBlockDiffUnsafe(newDep, oldDep)
    }
    val toRemove = diffs.map(_.toAdd.flatMap(_.nonCoinbase))
    val toAdd    = diffs.map(_.toRemove.flatMap(_.nonCoinbase.map((_, 1.0))))
    if (toAdd.sumBy(_.length) == 0) Normal(toRemove) else Reorg(toRemove, toAdd)
  }

  def updateMemPoolUnsafe(mainGroup: Int, newDeps: BlockDeps): Unit = {
    val oldDeps = getBestDeps(GroupIndex.unsafe(mainGroup))
    calMemPoolChangesUnsafe(mainGroup, oldDeps, newDeps) match {
      case Normal(toRemove) =>
        val removed = toRemove.foldWithIndex(0) { (sum, txs, toGroup) =>
          val index = ChainIndex.unsafe(mainGroup, toGroup)
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

  def updateBestDepsUnsafe(): Unit

  def calBestDepsUnsafe(group: GroupIndex): BlockDeps

  private def collectTransactions(chainIndex: ChainIndex): AVector[Transaction] = {
    getPool(chainIndex).collectForBlock(chainIndex, mempoolSetting.txMaxNumberPerBlock)
  }

  // Reduce height by 3 to make tx valid with high probability in case of forks
  def reduceHeight(height: Int): Int = {
    val newHeight = height - 3
    if (newHeight >= ALF.GenesisHeight) newHeight else ALF.GenesisHeight
  }

  def prepareBlockFlow(chainIndex: ChainIndex): IOResult[BlockFlowTemplate] = {
    assume(brokerConfig.contains(chainIndex.from))
    val singleChain = getBlockChain(chainIndex)
    val bestDeps    = getBestDeps(chainIndex.from)
    for {
      target <- singleChain.getHashTarget(bestDeps.getOutDep(chainIndex.to))
      height <- getBestHeight(chainIndex).map(reduceHeight)
    } yield {
      val transactions = collectTransactions(chainIndex)
      BlockFlowTemplate(chainIndex, height, bestDeps.deps, target, transactions)
    }
  }

  def prepareBlockFlowUnsafe(chainIndex: ChainIndex): BlockFlowTemplate = {
    assume(brokerConfig.contains(chainIndex.from))
    val singleChain  = getBlockChain(chainIndex)
    val bestDeps     = getBestDeps(chainIndex.from)
    val target       = Utils.unsafe(singleChain.getHashTarget(bestDeps.getOutDep(chainIndex.to)))
    val height       = Utils.unsafe(getBestHeight(chainIndex).map(reduceHeight))
    val transactions = collectTransactions(chainIndex)
    BlockFlowTemplate(chainIndex, height, bestDeps.deps, target, transactions)
  }
}

trait SyncUtils {
  def getInterCliqueSyncInfo(brokerConfig: BrokerInfo): SyncInfo

  def getIntraCliqueSyncInfo(remoteBroker: BrokerInfo): SyncInfo

  def getIntraCliqueSyncHashesUnsafe(remoteBroker: BrokerInfo): AVector[AVector[Hash]]
}
