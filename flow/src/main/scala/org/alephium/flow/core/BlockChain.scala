package org.alephium.flow.core

import org.alephium.flow.Utils
import org.alephium.flow.core.BlockChain.ChainDiff
import org.alephium.flow.io._
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

  def add(block: Block, weight: BigInt): IOResult[Unit] = {
    assume {
      val assertion = for {
        isNewIncluded    <- contains(block.hash)
        isParentIncluded <- contains(block.parentHash)
      } yield !isNewIncluded && isParentIncluded
      assertion.getOrElse(false)
    }

    for {
      _ <- persistBlock(block)
      _ <- add(block.header, weight)
    } yield ()
  }

  def addGenesis(block: Block): IOResult[Unit] = {
    for {
      _ <- persistBlock(block)
      _ <- addGenesis(block.header)
    } yield ()
  }

  protected def persistBlock(block: Block): IOResult[Unit] = {
    blockStorage.put(block)
  }

  protected def persistBlockUnsafe(block: Block): Unit = {
    blockStorage.putUnsafe(block)
    ()
  }

  def calBlockDiffUnsafe(newTip: Hash, oldTip: Hash): ChainDiff = {
    val hashDiff = Utils.unsafe(calHashDiff(newTip, oldTip))
    ChainDiff(hashDiff.toRemove.map(getBlockUnsafe), hashDiff.toAdd.map(getBlockUnsafe))
  }
}

object BlockChain {
  def fromGenesisUnsafe(storages: Storages)(chainIndex: ChainIndex)(
      implicit config: PlatformConfig): BlockChain = {
    val genesisBlock = config.genesisBlocks(chainIndex.from.value)(chainIndex.to.value)
    createUnsafe(chainIndex, genesisBlock, storages)
  }

  def createUnsafe(
      chainIndex: ChainIndex,
      rootBlock: Block,
      storages: Storages
  )(implicit _config: PlatformConfig): BlockChain = {
    new BlockChain {
      override implicit val config    = _config
      override val blockStorage       = storages.blockStorage
      override val headerStorage      = storages.headerStorage
      override val blockStateStorage  = storages.blockStateStorage
      override val heightIndexStorage = storages.nodeStateStorage.heightIndexStorage(chainIndex)
      override val tipsDB             = storages.nodeStateStorage.hashTreeTipsDB(chainIndex)
      override val genesisHash: Hash  = rootBlock.hash

      require(this.addGenesis(rootBlock).isRight)
    }
  }

  final case class ChainDiff(toRemove: AVector[Block], toAdd: AVector[Block])
}
