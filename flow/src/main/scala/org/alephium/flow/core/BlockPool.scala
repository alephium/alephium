package org.alephium.flow.core

import org.alephium.crypto.Keccak256
import org.alephium.flow.io.IOResult
import org.alephium.protocol.model.{Block, FlowData}
import org.alephium.util.AVector

trait BlockPool extends BlockHashPool {

  def contains(block: Block): Boolean = contains(block.hash)

  // TODO: refactor and merge contains and includes
  def includes[T <: FlowData](data: T): Boolean = contains(data.hash)

  // Assuming the hash is in the pool
  def getBlock(hash: Keccak256): IOResult[Block]

  // Assuming the block is verified
  def add(block: Block, weight: Int): IOResult[Unit]

  // Assuming the block is verified
  def add(block: Block, parentHash: Keccak256, weight: Int): IOResult[Unit]

  def getBlocks(hashes: AVector[Keccak256]): IOResult[AVector[Block]] = {
    hashes.filter(contains).mapE(getBlock)
  }

  def getBlocksAfter(locator: Keccak256): IOResult[AVector[Block]] = {
    getHashesAfter(locator).mapE(getBlock)
  }

  def getHeight(block: Block): Int = getHeight(block.hash)

  def getWeight(block: Block): Int = getWeight(block.hash)

  // TODO: use ChainSlice instead of AVector[Block]
  def getBlockSlice(hash: Keccak256): IOResult[AVector[Block]] = {
    getBlockHashSlice(hash).mapE(getBlock)
  }
  def getBlockSlice(block: Block): IOResult[AVector[Block]] = getBlockSlice(block.hash)

  def isTip(block: Block): Boolean = isTip(block.hash)
}
