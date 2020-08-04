package org.alephium.flow.core

import org.alephium.io.IOResult
import org.alephium.protocol.Hash
import org.alephium.protocol.model.BlockHeader
import org.alephium.util.{AVector, TimeStamp}

trait BlockHeaderPool extends BlockHashPool {

  def contains(bh: BlockHeader): IOResult[Boolean] = contains(bh.hash)

  // Assuming the entity is in the pool
  def getBlockHeader(hash: Hash): IOResult[BlockHeader]
  def getBlockHeaderUnsafe(hash: Hash): BlockHeader

  def add(header: BlockHeader, weight: BigInt): IOResult[Unit]

  def getHeadersAfter(locator: Hash): IOResult[AVector[BlockHeader]] = {
    for {
      hashes  <- getHashesAfter(locator)
      headers <- hashes.mapE(getBlockHeader)
    } yield headers
  }

  def getHeight(bh: BlockHeader): IOResult[Int] = getHeight(bh.hash)

  def getWeight(bh: BlockHeader): IOResult[BigInt] = getWeight(bh.hash)

  def isTip(bh: BlockHeader): Boolean = isTip(bh.hash)

  def getHeightedBlockHeaders(fromTs: TimeStamp,
                              toTs: TimeStamp): IOResult[AVector[(BlockHeader, Int)]]
}
