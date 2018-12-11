package org.alephium.storage
import org.alephium.protocol.model.Block

trait SingleChain extends BlockPool {

  def add(block: Block, weight: Int): Boolean
}
