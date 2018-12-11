package org.alephium.flow

import org.alephium.protocol.model.Block

class ChainSlice(val blocks: Seq[Block]) {
  assert(blocks.nonEmpty)
}

object ChainSlice {
  // TODO: make this safer
  def apply(blocks: Seq[Block]): ChainSlice = {
    assert(blocks.tail.zip(blocks.init).forall {
      case (current, previous) => current.prevBlockHash == previous.hash
    })
    new ChainSlice(blocks)
  }
}
