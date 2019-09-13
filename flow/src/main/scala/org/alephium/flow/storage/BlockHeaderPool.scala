package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.io.IOResult
import org.alephium.protocol.model.BlockHeader
import org.alephium.util.AVector

trait BlockHeaderPool extends BlockHashPool {

  def contains(bh: BlockHeader): Boolean = contains(bh.hash)

  // Assuming the entity is in the pool
  def getBlockHeader(hash: Keccak256): IOResult[BlockHeader]
  def getBlockHeaderUnsafe(hash: Keccak256): BlockHeader

  def add(header: BlockHeader, weight: Int): IOResult[Unit]

  def add(header: BlockHeader, parentHash: Keccak256, weight: Int): IOResult[Unit]

  def getHeaders(locators: AVector[Keccak256]): IOResult[AVector[BlockHeader]] = {
    locators.filter(contains).mapE(getBlockHeader)
  }

  def getHeight(bh: BlockHeader): Int = getHeight(bh.hash)

  def getWeight(bh: BlockHeader): Int = getWeight(bh.hash)

  def isTip(bh: BlockHeader): Boolean = isTip(bh.hash)
}
