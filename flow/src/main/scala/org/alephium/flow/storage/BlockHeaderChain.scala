package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.flow.io.{Database, IOResult}
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
    addHeader(header).map { _ =>
      addHash(header.hash, parent, weight)
    }
  }

  protected def addHeader(header: BlockHeader): IOResult[Unit] = {
    headerDB.putHeader(header)
  }

  protected def addHeaderUnsafe(header: BlockHeader): Unit = {
    headerDB.putHeaderUnsafe(header)
  }

  def getConfirmedHeader(height: Int): IOResult[Option[BlockHeader]] = {
    getConfirmedHash(height) match {
      case Some(hash) => headerDB.getHeader(hash).map(Some.apply)
      case None       => Right(None)
    }
  }

  def getHashTargetUnsafe(hash: Keccak256): BigInt = {
    assert(contains(hash))
    val header    = getBlockHeaderUnsafe(hash)
    val height    = getHeight(hash)
    val refHeight = height - config.retargetInterval
    if (refHeight > 0) {
      val refHash   = getPredecessor(hash, refHeight)
      val refHeader = getBlockHeaderUnsafe(refHash)
      val timeSpan  = header.timestamp - refHeader.timestamp
      getTarget(header, timeSpan)
    } else config.maxMiningTarget
  }

  def getHashTarget(hash: Keccak256): IOResult[BigInt] = {
    assert(contains(hash))
    getBlockHeader(hash).flatMap { header =>
      val height    = getHeight(hash)
      val refHeight = height - config.retargetInterval
      if (refHeight > 0) {
        val refHash = getPredecessor(hash, refHeight)
        getBlockHeader(refHash).map { refHeader =>
          val timeSpan = header.timestamp - refHeader.timestamp
          getTarget(header, timeSpan)
        }
      } else Right(config.maxMiningTarget)
    }
  }

  def getTarget(header: BlockHeader, timeSpan: Long): BigInt = {
    header.target * timeSpan / config.expectedTimeSpan
  }
}

object BlockHeaderChain {
  def fromGenesisUnsafe(genesis: Block)(implicit config: PlatformConfig): BlockHeaderChain =
    createUnsafe(genesis.header, 0, 0)

  private def createUnsafe(rootHeader: BlockHeader, initialHeight: Int, initialWeight: Int)(
      implicit _config: PlatformConfig): BlockHeaderChain = {
    val rootNode = BlockHashChain.Root(rootHeader.hash, initialHeight, initialWeight)

    new BlockHeaderChain {
      override val headerDB: Database                  = _config.headerDB
      override implicit def config: PlatformConfig     = _config
      override protected def root: BlockHashChain.Root = rootNode

      this.addHeaderUnsafe(rootHeader)
      this.addNode(rootNode)
    }
  }
}
