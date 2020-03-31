package org.alephium.flow.core

import org.alephium.flow.io.IOResult
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.model.BlockHeader
import org.alephium.util.AVector

trait BlockHeaderPool extends BlockHashPool {

  def contains(bh: BlockHeader): Boolean = contains(bh.hash)

  // Assuming the entity is in the pool
  def getBlockHeader(hash: Hash): IOResult[BlockHeader]
  def getBlockHeaderUnsafe(hash: Hash): BlockHeader

  def add(header: BlockHeader, weight: Int): IOResult[Unit]

  def add(header: BlockHeader, parentHash: Hash, weight: Int): IOResult[Unit]

  def getHeaders(locators: AVector[Hash]): IOResult[AVector[BlockHeader]] = {
    locators.filter(contains).mapE(getBlockHeader)
  }

  def getHeight(bh: BlockHeader): Int = getHeight(bh.hash)

  def getWeight(bh: BlockHeader): Int = getWeight(bh.hash)

  def isTip(bh: BlockHeader): Boolean = isTip(bh.hash)
}
