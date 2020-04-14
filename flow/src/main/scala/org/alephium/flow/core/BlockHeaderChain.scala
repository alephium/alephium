package org.alephium.flow.core

import org.alephium.flow.io._
import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.model.{BlockHeader, ChainIndex}
import org.alephium.util.{AVector, TimeStamp}

trait BlockHeaderChain extends BlockHeaderPool with BlockHashChain {

  def headerStorage: BlockHeaderStorage

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
      parentState <- getState(parentHash)
      _           <- addHeader(header)
      _ <- addHash(header.hash,
                   parentHash,
                   parentState.height + 1,
                   weight,
                   parentState.chainWeight + header.target,
                   header.timestamp)
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
  def fromGenesisUnsafe(storages: Storages)(chainIndex: ChainIndex)(
      implicit config: PlatformConfig): BlockHeaderChain = {
    val genesisBlock = config.genesisBlocks(chainIndex.from.value)(chainIndex.to.value)
    createUnsafe(chainIndex, genesisBlock.header, storages)
  }

  def createUnsafe(
      chainIndex: ChainIndex,
      rootHeader: BlockHeader,
      storages: Storages
  )(implicit _config: PlatformConfig): BlockHeaderChain = {
    new BlockHeaderChain {
      override implicit val config    = _config
      override val headerStorage      = storages.headerStorage
      override val blockStateStorage  = storages.blockStateStorage
      override val heightIndexStorage = storages.nodeStateStorage.heightIndexStorage(chainIndex)
      override val chainStateStorage  = storages.nodeStateStorage.chainStateStorage(chainIndex)
      override val genesisHash        = rootHeader.hash

      require(this.addGenesis(rootHeader).isRight)
    }
  }
}
