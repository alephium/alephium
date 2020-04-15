package org.alephium.flow.model

import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.GroupIndex
import org.alephium.util.AVector

/*
 * There are 2 * groups - 1 dependent hashes for each block
 * The first groups - 1 hashes are for the incoming chains of a specific group
 * The last groups hashes are for the outcoming chains of a specific group
 */
final case class BlockDeps(deps: AVector[Hash]) {
  def getOutDep(to: GroupIndex)(implicit config: GroupConfig): Hash = {
    deps.takeRight(config.groups)(to.value)
  }

  def outDeps(implicit config: GroupConfig): AVector[Hash] = {
    deps.takeRight(config.groups)
  }
}
