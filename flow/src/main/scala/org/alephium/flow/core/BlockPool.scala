package org.alephium.flow.core

import org.alephium.flow.io.IOResult
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.model.{Block, FlowData}
import org.alephium.util.AVector

trait BlockPool extends BlockHashPool {

  def contains(block: Block): IOResult[Boolean] = contains(block.hash)

  // TODO: refactor and merge contains and includes
  def includes[T <: FlowData](data: T): IOResult[Boolean] = contains(data.hash)

  // Assuming the hash is in the pool
  def getBlock(hash: Hash): IOResult[Block]

  // Assuming the block is verified
  def add(block: Block, weight: BigInt): IOResult[Unit]

  def getBlocksAfter(locator: Hash): IOResult[AVector[Block]] = {
    for {
      hashes <- getHashesAfter(locator)
      blocks <- hashes.mapE(getBlock)
    } yield blocks
  }

  def getHeight(block: Block): IOResult[Int] = getHeight(block.hash)

  def getWeight(block: Block): IOResult[BigInt] = getWeight(block.hash)

  // TODO: use ChainSlice instead of AVector[Block]
  def getBlockSlice(hash: Hash): IOResult[AVector[Block]] = {
    for {
      hashes <- getBlockHashSlice(hash)
      blocks <- hashes.mapE(getBlock)
    } yield blocks
  }
  def getBlockSlice(block: Block): IOResult[AVector[Block]] = getBlockSlice(block.hash)

  def isTip(block: Block): Boolean = isTip(block.hash)
}
