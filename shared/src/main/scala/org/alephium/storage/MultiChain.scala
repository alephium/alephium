package org.alephium.storage
import org.alephium.protocol.model.Block

trait MultiChain extends BlockPool {

  def add(block: Block): AddBlockResult
}
