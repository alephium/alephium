package org.alephium.flow.core

import org.alephium.flow.core.FlowHandler.BlockFlowTemplate
import org.alephium.flow.core.mempool.MemPool
import org.alephium.flow.io.IOResult
import org.alephium.flow.model.BlockDeps
import org.alephium.protocol.model.{ChainIndex, GroupIndex, Transaction}
import org.alephium.util.AVector

trait FlowUtils extends MultiChain with BlockFlowState {

  val mempools = AVector.tabulate(config.groupNumPerBroker) { idx =>
    val group = GroupIndex(brokerInfo.groupFrom + idx)
    MemPool.empty(group)
  }

  def getBestDeps(groupIndex: GroupIndex): BlockDeps

  def calBestDeps(): IOResult[Unit]

  def calBestDepsUnsafe(): Unit

  private def getPool(chainIndex: ChainIndex): MemPool = {
    mempools(chainIndex.from.value - brokerInfo.groupFrom)
  }

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
