package org.alephium.storage
import org.alephium.crypto.Keccak256
import org.alephium.protocol.model.Block
import org.alephium.storage.BlockFlow.ChainIndex

trait MultiChain extends BlockPool {

  def getChain(chainIndex: ChainIndex): SingleChain

  def getChain(block: Block): SingleChain = getChain(getIndex(block))

  def getChain(hash: Keccak256): SingleChain = getChain(getIndex(hash))

  def getIndex(block: Block): ChainIndex = {
    getIndex(block.hash)
  }

  def contains(hash: Keccak256): Boolean = {
    val chain = getChain(hash)
    chain.contains(hash)
  }

  def getIndex(hash: Keccak256): ChainIndex = {
    ChainIndex.fromHash(hash)
  }

  def add(block: Block): AddBlockResult

  def getBlock(hash: Keccak256): Block = {
    getChain(hash).getBlock(hash)
  }

  def getBlocks(locator: Keccak256): Seq[Block] = {
    getChain(locator).getBlocks(locator)
  }

  def isTip(hash: Keccak256): Boolean = {
    getChain(hash).isTip(hash)
  }

  def getHeight(hash: Keccak256): Int = {
    getChain(hash).getHeight(hash)
  }

  def getWeight(hash: Keccak256): Int = {
    getChain(hash).getWeight(hash)
  }

  def getBlockSlice(hash: Keccak256): Seq[Block] = getChain(hash).getBlockSlice(hash)
}
