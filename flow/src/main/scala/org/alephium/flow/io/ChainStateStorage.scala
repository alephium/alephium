package org.alephium.flow.io

import org.alephium.flow.core.BlockHashChain

trait ChainStateStorage {
  def updateState(state: BlockHashChain.State): IOResult[Unit]

  def loadState(): IOResult[BlockHashChain.State]

  def clearState(): IOResult[Unit]
}
