package org.alephium.flow.core

import org.alephium.flow.io.{BlockHeaderStorage, HashTreeTipsDB, HeightIndexStorage, IOResult}
import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.model.{Block, BlockHeader, ChainIndex}
import org.alephium.util.{AVector, TimeStamp}

trait BlockHeaderChain extends BlockHeaderPool with BlockHashChain {

  val headerStorage: BlockHeaderStorage = config.storages.headerStorage

  def getBlockHeader(hash: Hash): IOResult[BlockHeader] = {
    headerStorage.get(hash)
  }

  def getBlockHeaderUnsafe(hash: Hash): BlockHeader = {
    headerStorage.getUnsafe(hash)
  }

  def getParentHash(hash: Hash): IOResult[Hash] = {
    getBlockHeader(hash).map(_.parentHash)
  }

  def getTimestamp(hash: Hash): IOResult[TimeStamp] = {
    getBlockHeader(hash).map(_.timestamp)
  }

  def add(header: BlockHeader, weight: BigInt): IOResult[Unit] = {
    assume(!header.isGenesis)
    val parentHash = header.parentHash
    assume {
      val assertion = for {
        bool1 <- contains(header.hash)
        bool2 <- contains(parentHash)
      } yield !bool1 && bool2
      assertion.getOrElse(false)
    }

    for {
      parentHeight <- getHeight(parentHash)
      _            <- addHeader(header)
      _            <- addHash(header.hash, parentHash, parentHeight + 1, weight, header.timestamp)
    } yield ()
  }

  def addGenesis(header: BlockHeader): IOResult[Unit] = {
    assume(header.hash == genesisHash)
    for {
      _ <- addHeader(header)
      _ <- addGenesis(header.hash)
    } yield ()
  }

  protected def addHeader(header: BlockHeader): IOResult[Unit] = {
    headerStorage.put(header)
  }

  protected def addHeaderUnsafe(header: BlockHeader): Unit = {
    headerStorage.putUnsafe(header)
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def chainBack(hash: Hash, heightUntil: Int): IOResult[AVector[Hash]] = {
    getHeight(hash).flatMap {
      case height if height > heightUntil =>
        getBlockHeader(hash).flatMap { header =>
          if (height > heightUntil + 1) chainBack(header.parentHash, heightUntil).map(_ :+ hash)
          else Right(AVector(hash))
        }
      case _ => Right(AVector.empty)
    }
  }

  def getHashTarget(hash: Hash): IOResult[BigInt] = {
    for {
      header    <- getBlockHeader(hash)
      newTarget <- calHashTarget(hash, header.target)
    } yield newTarget
  }
}

object BlockHeaderChain {
  def fromGenesisUnsafe(chainIndex: ChainIndex)(
      implicit config: PlatformConfig): BlockHeaderChain = {
    val genesisBlock = config.genesisBlocks(chainIndex.from.value)(chainIndex.to.value)
    val tipsDB       = config.storages.nodeStateStorage.hashTreeTipsDB(chainIndex)
    fromGenesisUnsafe(genesisBlock, tipsDB)
  }

  def fromGenesisUnsafe(genesis: Block, tipsDB: HashTreeTipsDB)(
      implicit config: PlatformConfig): BlockHeaderChain =
    createUnsafe(genesis.header, tipsDB)

  private def createUnsafe(
      rootHeader: BlockHeader,
      _tipsDB: HashTreeTipsDB
  )(implicit _config: PlatformConfig): BlockHeaderChain = {
    new BlockHeaderChain {
      override implicit def config: PlatformConfig        = _config
      override val heightIndexStorage: HeightIndexStorage = _config.storages.heightIndexStorage
      override val tipsDB: HashTreeTipsDB                 = _tipsDB
      override val genesisHash: Hash                      = rootHeader.hash

      require(this.addGenesis(rootHeader).isRight)
    }
  }
}
