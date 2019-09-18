package org.alephium.flow.core

import scala.collection.mutable.ArrayBuffer

import org.alephium.protocol.model.Block

class ConfirmedChain() {
  private val blocks: ArrayBuffer[Block] = ArrayBuffer.empty

  def add(block: Block): Unit = {
    blocks += block
  }
}

object ConfirmedChain {
  def apply(): ConfirmedChain = new ConfirmedChain()
}
