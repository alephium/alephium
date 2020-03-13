package org.alephium.flow.core

import org.alephium.crypto.Keccak256
import org.alephium.flow.io.{HeaderDB, IOResult}
import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.model.{Block, BlockHeader}

trait BlockHeaderChain extends BlockHeaderPool with BlockHashChain {

  def headerDB: HeaderDB

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

  def getConfirmedHeader(height: Int): IOResult[Option[BlockHeader]] = {
    getConfirmedHash(height) match {
      case Some(hash) => headerDB.getHeader(hash).map(Some.apply)
      case None       => Right(None)
    }
  }

  def getHashTargetUnsafe(hash: Keccak256): BigInt = {
    assert(contains(hash))
    val header = getBlockHeaderUnsafe(hash)
    calHashTarget(hash, header.target)
  }

  def getHashTarget(hash: Keccak256): IOResult[BigInt] = {
    assert(contains(hash))
    getBlockHeader(hash).map(header => calHashTarget(hash, header.target))
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
      override val headerDB: HeaderDB                  = _config.headerDB
      override implicit def config: PlatformConfig     = _config
      override protected def root: BlockHashChain.Root = rootNode

      this.addHeaderUnsafe(rootHeader)
      this.addNode(rootNode)
    }
  }
}
