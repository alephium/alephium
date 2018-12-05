package org.alephium.flow.storage

import org.alephium.flow.io.IOResult
import org.alephium.flow.model.BlockDeps
import org.alephium.flow.storage.FlowHandler.BlockFlowTemplate
import org.alephium.protocol.model.ChainIndex

trait FlowUtils extends MultiChain {

  def getBestDepsUnsafe(chainIndex: ChainIndex): BlockDeps

  def getBestDeps(chainIndex: ChainIndex): IOResult[BlockDeps]

  def prepareBlockFlow(chainIndex: ChainIndex): IOResult[BlockFlowTemplate] = {
    val singleChain = getBlockChain(chainIndex)
    for {
      bestDeps <- getBestDeps(chainIndex)
      target   <- singleChain.getHashTarget(bestDeps.getChainHash)
    } yield BlockFlowTemplate(bestDeps.deps, target)
  }

  def prepareBlockFlowUnsafe(chainIndex: ChainIndex): BlockFlowTemplate = {
    val singleChain = getBlockChain(chainIndex)
    val bestDeps    = getBestDepsUnsafe(chainIndex)
    val target      = singleChain.getHashTargetUnsafe(bestDeps.getChainHash)
    BlockFlowTemplate(bestDeps.deps, target)
  }
}
