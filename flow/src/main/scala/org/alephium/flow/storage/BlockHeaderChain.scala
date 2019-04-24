package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.flow.io.{Database, IOError, IOResult}
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
      addHash(header.hash, parent, weight, header.timestamp)
    }
  }

  protected def addHeader(header: BlockHeader): IOResult[Unit] = {
    headerDB.putHeader(header)
  }

  protected def addHeaderUnsafe(header: BlockHeader): Unit = {
    headerDB.putHeaderUnsafe(header)
  }

  // TODO: remove this
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
    try {
      Right(getHashTargetUnsafe(hash))
    } catch {
      case e: Exception => Left(IOError(e))
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
    val timestamp = rootHeader.timestamp
    val rootNode  = BlockHashChain.Root(rootHeader.hash, initialHeight, initialWeight, timestamp)

    new BlockHeaderChain {
      override val headerDB: Database                  = _config.db
      override implicit def config: PlatformConfig     = _config
      override protected def root: BlockHashChain.Root = rootNode

      this.addHeaderUnsafe(rootHeader)
      this.addNode(rootNode)
    }
  }
}
