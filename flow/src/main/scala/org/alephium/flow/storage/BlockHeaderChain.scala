package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.protocol.model.{Block, BlockHeader}

trait BlockHeaderChain extends BlockHeaderPool with BlockHashChain {

  def headerDB: Database

  def getBlockHeader(hash: Keccak256): IOResult[BlockHeader] = {
    headerDB.getHeader(hash)
  }

  def getBlockHeaderUnsafe(hash: Keccak256): BlockHeader = {
    headerDB.getHeaderUnsafe(hash)
  }

  def add(blockHeader: BlockHeader, weight: Int): IOResult[Unit] = {
    add(blockHeader, blockHeader.parentHash, weight)
  }

  def add(header: BlockHeader, parentHash: Keccak256, weight: Int): IOResult[Unit] = {
    assert(!contains(header.hash) && contains(parentHash))
    val parent = blockHashesTable(parentHash)
    addHash(header.hash, parent, weight)
    addHeader(header)
  }

  protected def addHeader(header: BlockHeader): IOResult[Unit] = {
    headerDB.putHeader(header)
  }

  def getConfirmedHeader(height: Int): IOResult[Option[BlockHeader]] = {
    getConfirmedHash(height) match {
      case Some(hash) => headerDB.getHeader(hash).map(Option.apply)
      case None       => Right(None)
    }
  }

  def getHashTarget(hash: Keccak256): IOResult[BigInt] = {
    assert(contains(hash))
    getBlockHeader(hash).flatMap { header =>
      val height    = getHeight(hash)
      val refHeight = height - config.retargetInterval
      if (refHeight >= 0) {
        val refHash = getPredecessor(hash, refHeight)
        getBlockHeader(refHash).map { refHeader =>
          val timeSpan = header.timestamp - refHeader.timestamp
          val retarget = header.target * config.retargetInterval * config.blockTargetTime.toMillis / timeSpan
          retarget
        }
      } else Right(config.maxMiningTarget)
    }
  }
}

object BlockHeaderChain {
  def fromGenesis(genesis: Block)(implicit config: PlatformConfig): BlockHeaderChain =
    apply(genesis.header, 0, 0)

  def apply(rootHeader: BlockHeader, initialHeight: Int, initialWeight: Int)(
      implicit _config: PlatformConfig): BlockHeaderChain = {
    val rootNode = BlockHashChain.Root(rootHeader.hash, initialHeight, initialWeight)

    new BlockHeaderChain {
      override val headerDB: Database                  = _config.headerDB
      override implicit def config: PlatformConfig     = _config
      override protected def root: BlockHashChain.Root = rootNode

      this.addNode(rootNode)
      this.addHeader(rootHeader)
    }
  }
}
