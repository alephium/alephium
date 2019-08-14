package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.io.IOResult
import org.alephium.protocol.model.Block
import org.alephium.util.AVector

trait BlockPool extends BlockHashPool {

  def contains(block: Block): Boolean = contains(block.hash)

  // Assuming the hash is in the pool
  def getBlock(hash: Keccak256): IOResult[Block]

  // Assuming the block is verified
  def add(block: Block, weight: Int): IOResult[Unit]

  // Assuming the block is verified
  def add(block: Block, parentHash: Keccak256, weight: Int): IOResult[Unit]

  def getBlocks(locators: AVector[Keccak256]): IOResult[AVector[Block]] = {
    locators.filter(contains).traverse(getBlock)
  }

  def getHeight(block: Block): Int = getHeight(block.hash)

  def getWeight(block: Block): Int = getWeight(block.hash)

  // TODO: use ChainSlice instead of AVector[Block]
  def getBlockSlice(hash: Keccak256): IOResult[AVector[Block]] = {
    getBlockHashSlice(hash).traverse(getBlock)
  }
  def getBlockSlice(block: Block): IOResult[AVector[Block]] = getBlockSlice(block.hash)

  def isTip(block: Block): Boolean = isTip(block.hash)
}
