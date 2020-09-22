package org.alephium.flow.core

import org.alephium.flow.model.BlockDeps
import org.alephium.protocol.Hash
import org.alephium.protocol.model.GroupIndex
import org.alephium.util.AVector

final case class FlowTips(targetGroup: GroupIndex, inTips: AVector[Hash], outTips: AVector[Hash]) {
  def toBlockDeps: BlockDeps = BlockDeps(inTips ++ outTips)
}

object FlowTips {
  final case class Light(inTips: AVector[Hash], outTip: Hash)
}
