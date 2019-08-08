package org.alephium.flow.storage

import org.alephium.flow.io.IOResult
import org.alephium.flow.model.BlockDeps
import org.alephium.flow.storage.FlowHandler.BlockFlowTemplate
import org.alephium.protocol.model.{ChainIndex, GroupIndex}

trait FlowUtils extends MultiChain with TransactionPool {

  def getBestDeps(groupIndex: GroupIndex): BlockDeps

  def calBestDeps(): IOResult[Unit]

  def calBestDepsUnsafe(): Unit

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
