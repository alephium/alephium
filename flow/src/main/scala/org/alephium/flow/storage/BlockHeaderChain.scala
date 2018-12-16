package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.protocol.model.BlockHeader

import scala.collection.mutable.HashMap

trait BlockHeaderChain extends BlockHeaderPool with BlockHashChain {
  protected val blockHeadersTable: HashMap[Keccak256, BlockHeader]

  override def getBlockHeader(hash: Keccak256): BlockHeader = {
    blockHeadersTable(hash)
  }

  override def add(blockHeader: BlockHeader, weight: Int): AddBlockHeaderResult = {
    add(blockHeader, blockHeader.parentHash, weight)
  }

  override def add(blockHeader: BlockHeader,
                   parentHash: Keccak256,
                   weight: Int): AddBlockHeaderResult
}
