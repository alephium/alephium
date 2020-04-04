package org.alephium.flow.core

import org.alephium.flow.io.{BlockHeaderStorage, HashTreeTipsDB, IOResult}
import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.model.{Block, BlockHeader, ChainIndex}

trait BlockHeaderChain extends BlockHeaderPool with BlockHashChain {

  def headerDB: BlockHeaderStorage

  def getBlockHeader(hash: Hash): IOResult[BlockHeader] = {
    headerDB.get(hash)
  }

  def getBlockHeaderUnsafe(hash: Hash): BlockHeader = {
    headerDB.getUnsafe(hash)
  }

  def add(blockHeader: BlockHeader, weight: Int): IOResult[Unit] = {
    add(blockHeader, blockHeader.parentHash, weight)
  }

  def add(header: BlockHeader, parentHash: Hash, weight: Int): IOResult[Unit] = {
    assert(!contains(header.hash) && contains(parentHash))
    val parent = blockHashesTable(parentHash)
    addHeader(header).flatMap { _ =>
      addHash(header.hash, parent, weight, header.timestamp)
    }
  }

  protected def addHeader(header: BlockHeader): IOResult[Unit] = {
    headerDB.put(header)
  }

  protected def addHeaderUnsafe(header: BlockHeader): Unit = {
    headerDB.putUnsafe(header)
  }

  def getHashTargetUnsafe(hash: Hash): BigInt = {
    assert(contains(hash))
    val header = getBlockHeaderUnsafe(hash)
    calHashTarget(hash, header.target)
  }

  def getHashTarget(hash: Hash): IOResult[BigInt] = {
    assert(contains(hash))
    getBlockHeader(hash).map(header => calHashTarget(hash, header.target))
  }
}

object BlockHeaderChain {
  def fromGenesisUnsafe(chainIndex: ChainIndex)(
      implicit config: PlatformConfig): BlockHeaderChain = {
    val genesisBlock = config.genesisBlocks(chainIndex.from.value)(chainIndex.to.value)
    val tipsDB       = config.nodeStateDB.hashTreeTipsDB(chainIndex)
    fromGenesisUnsafe(genesisBlock, tipsDB)
  }

  def fromGenesisUnsafe(genesis: Block, tipsDB: HashTreeTipsDB)(
      implicit config: PlatformConfig): BlockHeaderChain =
    createUnsafe(genesis.header, 0, 0, tipsDB)

  private def createUnsafe(
      rootHeader: BlockHeader,
      initialHeight: Int,
      initialWeight: Int,
      _tipsDB: HashTreeTipsDB
  )(implicit _config: PlatformConfig): BlockHeaderChain = {
    val timestamp = rootHeader.timestamp
    val rootNode  = BlockHashChain.Root(rootHeader.hash, initialHeight, initialWeight, timestamp)

    new BlockHeaderChain {
      override val headerDB: BlockHeaderStorage        = _config.headerDB
      override val tipsDB: HashTreeTipsDB              = _tipsDB
      override implicit def config: PlatformConfig     = _config
      override protected def root: BlockHashChain.Root = rootNode

      this.addHeaderUnsafe(rootHeader)
      this.addNode(rootNode)
    }
  }
}
