package org.alephium.flow

import org.alephium.protocol.model.Block

import scala.collection.mutable.ArrayBuffer

class ConfirmedChain() {
  private val blocks: ArrayBuffer[Block] = ArrayBuffer.empty

  def add(block: Block): Unit = {
    require(block.prevBlockHash == blocks.last.hash)
    blocks += block
  }
}

object ConfirmedChain {
  def apply(): ConfirmedChain = new ConfirmedChain()
}
