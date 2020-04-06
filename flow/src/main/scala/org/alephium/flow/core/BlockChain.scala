package org.alephium.flow.core

import org.alephium.flow.core.BlockChain.ChainDiff
import org.alephium.flow.io.{BlockStorage, HashTreeTipsDB, IOResult}
import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.model.{Block, ChainIndex}
import org.alephium.util.AVector

trait BlockChain extends BlockPool with BlockHeaderChain with BlockHashChain {

  def blockStorage: BlockStorage

  def getBlock(hash: Hash): IOResult[Block] = {
    blockStorage.get(hash)
  }

  def getBlockUnsafe(hash: Hash): Block = {
    blockStorage.getUnsafe(hash)
  }

  def add(block: Block, weight: Int): IOResult[Unit] = {
    add(block, block.parentHash, weight)
  }

  def add(block: Block, parentHash: Hash, weight: Int): IOResult[Unit] = {
    assert(!contains(block.hash) && contains(parentHash))
    for {
      _ <- persistBlock(block)
      _ <- add(block.header, parentHash, weight)
    } yield ()
  }

  protected def persistBlock(block: Block): IOResult[Unit] = {
    blockStorage.put(block).right.map(_ => ())
    // TODO: handle transactions later
  }

  protected def persistBlockUnsafe(block: Block): Unit = {
    blockStorage.putUnsafe(block)
    ()
  }

  def calBlockDiffUnsafe(newTip: Hash, oldTip: Hash): ChainDiff = {
    val hashDiff = calHashDiff(newTip, oldTip)
    ChainDiff(hashDiff.toRemove.map(getBlockUnsafe), hashDiff.toAdd.map(getBlockUnsafe))
  }
}

object BlockChain {
  def fromGenesisUnsafe(chainIndex: ChainIndex)(implicit config: PlatformConfig): BlockChain = {
    val genesisBlock = config.genesisBlocks(chainIndex.from.value)(chainIndex.to.value)
    val tipsDB       = config.storages.nodeStateStorage.hashTreeTipsDB(chainIndex)
    fromGenesisUnsafe(genesisBlock, tipsDB)
  }

  def fromGenesisUnsafe(genesis: Block, tipsDB: HashTreeTipsDB)(
      implicit config: PlatformConfig): BlockChain =
    createUnsafe(genesis, 0, 0, tipsDB)

  private def createUnsafe(
      rootBlock: Block,
      initialHeight: Int,
      initialWeight: Int,
      _tipsDB: HashTreeTipsDB
  )(implicit _config: PlatformConfig): BlockChain = {
    val timestamp = rootBlock.header.timestamp
    val rootNode  = BlockHashChain.Root(rootBlock.hash, initialHeight, initialWeight, timestamp)

    new BlockChain {
      override val blockStorage                        = _config.storages.blockStorage
      override val headerStorage                       = _config.storages.headerStorage
      override val tipsDB                              = _tipsDB
      override implicit val config: PlatformConfig     = _config
      override protected def root: BlockHashChain.Root = rootNode

      this.persistBlockUnsafe(rootBlock)
      this.addHeaderUnsafe(rootBlock.header)
      this.addNode(rootNode)
    }
  }

  final case class ChainDiff(toRemove: AVector[Block], toAdd: AVector[Block])
}
