package org.alephium.flow.model

import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformProfile
import org.alephium.protocol.model.GroupIndex
import org.alephium.util.AVector

/*
 * There are 2 * groups - 1 dependent hashes for each block
 * The first groups - 1 hashes are for the other groups
 * The last groups hashes are for the chains related to the target group
 */
case class BlockDeps(deps: AVector[Keccak256]) {

  def getChainHash(to: GroupIndex)(implicit config: PlatformProfile): Keccak256 = {
    deps.takeRight(config.groups)(to.value)
  }
}
