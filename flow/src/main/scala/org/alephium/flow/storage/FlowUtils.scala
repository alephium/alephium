package org.alephium.flow.storage

import org.alephium.flow.io.IOResult
import org.alephium.flow.model.BlockDeps
import org.alephium.flow.storage.FlowHandler.BlockFlowTemplate
import org.alephium.protocol.model.{ChainIndex, GroupIndex}

trait FlowUtils extends MultiChain {

  def getBestDeps(groupIndex: GroupIndex): BlockDeps

  def calBestDeps(): IOResult[Unit]

  def calBestDepsUnsafe(): Unit

  def prepareBlockFlow(chainIndex: ChainIndex): IOResult[BlockFlowTemplate] = {
    assert(config.brokerId.contains(chainIndex.from))
    val singleChain = getBlockChain(chainIndex)
    val bestDeps    = getBestDeps(chainIndex.from)
    for {
      target <- singleChain.getHashTarget(bestDeps.getChainHash(chainIndex.to))
    } yield BlockFlowTemplate(chainIndex, bestDeps.deps, target)
  }

  def prepareBlockFlowUnsafe(chainIndex: ChainIndex): BlockFlowTemplate = {
    assert(config.brokerId.contains(chainIndex.from))
    val singleChain = getBlockChain(chainIndex)
    val bestDeps    = getBestDeps(chainIndex.from)
    val target      = singleChain.getHashTargetUnsafe(bestDeps.getChainHash(chainIndex.to))
    BlockFlowTemplate(chainIndex, bestDeps.deps, target)
  }
}
