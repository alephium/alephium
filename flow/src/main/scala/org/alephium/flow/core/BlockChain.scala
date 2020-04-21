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

  protected def addGenesis(block: Block): IOResult[Unit] = {
    for {
      _ <- persistBlock(block)
      _ <- addGenesis(block.header)
    } yield ()
  }

  override protected def loadFromStorage(): IOResult[Unit] = {
    super.loadFromStorage()
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
    val initialize   = initializeGenesis(genesisBlock)(_)
    createUnsafe(chainIndex, genesisBlock, storages, initialize)
  }

  def fromStorageUnsafe(storages: Storages)(chainIndex: ChainIndex)(
      implicit config: PlatformConfig): BlockChain = {
    val genesisBlock = config.genesisBlocks(chainIndex.from.value)(chainIndex.to.value)
    createUnsafe(chainIndex, genesisBlock, storages, initializeFromStorage)
  }

  def createUnsafe(
      chainIndex: ChainIndex,
      rootBlock: Block,
      storages: Storages,
      initialize: BlockChain => IOResult[Unit]
  )(implicit _config: PlatformConfig): BlockChain = {
    val blockchain: BlockChain = new BlockChain {
      override implicit val config    = _config
      override val blockStorage       = storages.blockStorage
      override val headerStorage      = storages.headerStorage
      override val blockStateStorage  = storages.blockStateStorage
      override val heightIndexStorage = storages.nodeStateStorage.heightIndexStorage(chainIndex)
      override val chainStateStorage  = storages.nodeStateStorage.chainStateStorage(chainIndex)
      override val genesisHash: Hash  = rootBlock.hash
    }

    Utils.unsafe(initialize(blockchain))
    blockchain
  }

  def initializeGenesis(genesisBlock: Block)(chain: BlockChain): IOResult[Unit] = {
    chain.addGenesis(genesisBlock)
  }

  def initializeFromStorage(chain: BlockChain): IOResult[Unit] = {
    chain.loadFromStorage()
  }

  final case class ChainDiff(toRemove: AVector[Block], toAdd: AVector[Block])
}
