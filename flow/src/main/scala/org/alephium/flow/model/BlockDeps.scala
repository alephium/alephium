package org.alephium.flow.model

import org.alephium.crypto.Keccak256
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.GroupIndex
import org.alephium.util.AVector

/*
 * There are 2 * groups - 1 dependent hashes for each block
 * The first groups - 1 hashes are for the incoming chains of a specific group
 * The last groups hashes are for the outcoming chains of a specific group
 */
case class BlockDeps(deps: AVector[Keccak256]) {
  def getChainHash(to: GroupIndex)(implicit config: GroupConfig): Keccak256 = {
    deps.takeRight(config.groups)(to.value)
  }

  def outDeps(implicit config: GroupConfig): AVector[Keccak256] = {
    deps.takeRight(config.groups)
  }
}
