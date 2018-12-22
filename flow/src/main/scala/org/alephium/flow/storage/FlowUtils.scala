package org.alephium.flow.storage

import org.alephium.flow.io.IOResult
import org.alephium.flow.model.BlockDeps
import org.alephium.flow.storage.FlowHandler.BlockFlowTemplate
import org.alephium.protocol.model.ChainIndex

trait FlowUtils extends MultiChain {

  def getBestDeps: BlockDeps

  def calBestDeps(): IOResult[BlockDeps]

  def calBestDepsUnsafe(): BlockDeps

  def prepareBlockFlow(chainIndex: ChainIndex): IOResult[BlockFlowTemplate] = {
    assert(chainIndex.from == config.mainGroup)
    val singleChain = getBlockChain(chainIndex)
    val bestDeps    = getBestDeps
    for {
      target <- singleChain.getHashTarget(bestDeps.getChainHash(chainIndex.to))
    } yield BlockFlowTemplate(chainIndex, bestDeps.deps, target)
  }

  def prepareBlockFlowUnsafe(chainIndex: ChainIndex): BlockFlowTemplate = {
    assert(chainIndex.from == config.mainGroup)
    val singleChain = getBlockChain(chainIndex)
    val bestDeps    = getBestDeps
    val target      = singleChain.getHashTargetUnsafe(bestDeps.getChainHash(chainIndex.to))
    BlockFlowTemplate(chainIndex, bestDeps.deps, target)
  }
}
