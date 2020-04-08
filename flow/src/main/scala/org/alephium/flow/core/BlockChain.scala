package org.alephium.flow.core

import org.alephium.flow.Utils
import org.alephium.flow.core.BlockChain.ChainDiff
import org.alephium.flow.io.{BlockStorage, HashTreeTipsDB, HeightIndexStorage, IOResult}
import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.model.{Block, ChainIndex}
import org.alephium.util.AVector

trait BlockChain extends BlockPool with BlockHeaderChain with BlockHashChain {

  val blockStorage: BlockStorage = config.storages.blockStorage

  def getBlock(hash: Hash): IOResult[Block] = {
    blockStorage.get(hash)
  }

  def getBlockUnsafe(hash: Hash): Block = {
    blockStorage.getUnsafe(hash)
  }

  def add(block: Block, weight: BigInt): IOResult[Unit] = {
    val parentHash = block.parentHash
    assume {
      val assertion = for {
        isNewIncluded    <- contains(block.hash)
        isParentIncluded <- contains(parentHash)
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
    blockStorage.put(block).right.map(_ => ())
    // TODO: handle transactions later
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
  def fromGenesisUnsafe(chainIndex: ChainIndex)(implicit config: PlatformConfig): BlockChain = {
    val genesisBlock       = config.genesisBlocks(chainIndex.from.value)(chainIndex.to.value)
    val tipsStorage        = config.storages.nodeStateStorage.hashTreeTipsDB(chainIndex)
    val heightIndexStorage = config.storages.nodeStateStorage.heightIndexStorage(chainIndex)
    fromGenesisUnsafe(genesisBlock, heightIndexStorage, tipsStorage)
  }

  def fromGenesisUnsafe(genesis: Block,
                        heightIndexStorage: HeightIndexStorage,
                        tipsStorage: HashTreeTipsDB)(implicit config: PlatformConfig): BlockChain =
    createUnsafe(genesis, heightIndexStorage, tipsStorage)

  private def createUnsafe(
      rootBlock: Block,
      _heightIndexStorage: HeightIndexStorage,
      _tipsDB: HashTreeTipsDB
  )(implicit _config: PlatformConfig): BlockChain = {
    new BlockChain {
      override implicit val config: PlatformConfig = _config
      override val heightIndexStorage              = _heightIndexStorage
      override val tipsDB                          = _tipsDB
      override val genesisHash: Hash               = rootBlock.hash

      require(this.addGenesis(rootBlock).isRight)
    }
  }

  final case class ChainDiff(toRemove: AVector[Block], toAdd: AVector[Block])
}
