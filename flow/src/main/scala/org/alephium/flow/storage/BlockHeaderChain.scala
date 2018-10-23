package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.protocol.model.{Block, BlockHeader}
import org.alephium.util.AVector

import scala.collection.mutable.HashMap

trait BlockHeaderChain extends BlockHeaderPool with BlockHashChain {
  protected val blockHeadersTable: HashMap[Keccak256, BlockHeader] = HashMap.empty

  def getBlockHeader(hash: Keccak256): BlockHeader = {
    blockHeadersTable(hash)
  }

  def add(blockHeader: BlockHeader, weight: Int): AddBlockHeaderResult = {
    add(blockHeader, blockHeader.parentHash, weight)
  }

  def add(header: BlockHeader, parentHash: Keccak256, weight: Int): AddBlockHeaderResult = {
    blockHeadersTable.get(header.hash) match {
      case Some(_) => AddBlockHeaderResult.AlreadyExisted
      case None =>
        blockHashesTable.get(parentHash) match {
          case Some(parent) =>
            addHash(header.hash, parent, weight)
            addHeader(header)
            AddBlockHeaderResult.Success
          case None =>
            AddBlockHeaderResult.MissingDeps(AVector(parentHash))
        }
    }
  }

  protected def addHeader(header: BlockHeader): Unit = {
    blockHeadersTable += header.hash -> header
  }
}

object BlockHeaderChain {
  def fromGenesis(genesis: Block)(implicit config: PlatformConfig): BlockHeaderChain =
    apply(genesis.blockHeader, 0, 0)

  def apply(rootHeader: BlockHeader, initialHeight: Int, initialWeight: Int)(
      implicit _config: PlatformConfig): BlockHeaderChain = {
    val rootNode = BlockHashChain.Root(rootHeader.hash, initialHeight, initialWeight)

    new BlockHeaderChain {
      override implicit def config: PlatformConfig     = _config
      override protected def root: BlockHashChain.Root = rootNode

      this.addNode(rootNode)
      this.addHeader(rootHeader)
    }
  }
}
