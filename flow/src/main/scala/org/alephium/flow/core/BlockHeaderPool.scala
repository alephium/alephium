package org.alephium.flow.core

import org.alephium.flow.io.IOResult
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.model.BlockHeader
import org.alephium.util.AVector

trait BlockHeaderPool extends BlockHashPool {

  def contains(bh: BlockHeader): IOResult[Boolean] = contains(bh.hash)

  // Assuming the entity is in the pool
  def getBlockHeader(hash: Hash): IOResult[BlockHeader]
  def getBlockHeaderUnsafe(hash: Hash): BlockHeader

  def add(header: BlockHeader, weight: BigInt): IOResult[Unit]

  // TODO: refactor this purely for syncing
  def getHeaders(locators: AVector[Hash]): IOResult[AVector[BlockHeader]] = {
    for {
      validLocators <- locators.filterE(contains)
      headers       <- validLocators.mapE(getBlockHeader)
    } yield headers
  }

  def getHeight(bh: BlockHeader): IOResult[Int] = getHeight(bh.hash)

  def getWeight(bh: BlockHeader): IOResult[BigInt] = getWeight(bh.hash)

  def isTip(bh: BlockHeader): Boolean = isTip(bh.hash)
}
