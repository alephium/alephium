package org.alephium.flow.core

import org.alephium.flow.Utils
import org.alephium.flow.io._
import org.alephium.flow.platform.PlatformConfig
import org.alephium.io.IOResult
import org.alephium.protocol.{ALF, Hash}
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

  protected def addGenesis(header: BlockHeader): IOResult[Unit] = {
    assume(header.hash == genesisHash)
    for {
      _ <- addHeader(header)
      _ <- addGenesis(header.hash)
    } yield ()
  }

  override protected def loadFromStorage(): IOResult[Unit] = {
    super.loadFromStorage()
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

  def getHeightedBlockHeaders(fromTs: TimeStamp,
                              toTs: TimeStamp): IOResult[AVector[(BlockHeader, Int)]] =
    for {
      height <- maxHeight
      result <- searchByTimestampHeight(height, AVector.empty, fromTs, toTs)
    } yield result

  //TODO Make it tailrec
  //TODO Use binary search with the height params to find quicklier all our blocks.
  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def searchByTimestampHeight(height: Int,
                                      prev: AVector[(BlockHeader, Int)],
                                      fromTs: TimeStamp,
                                      toTs: TimeStamp): IOResult[AVector[(BlockHeader, Int)]] = {
    getHashes(height).flatMap { hashes =>
      hashes.mapE(getBlockHeader).flatMap { headers =>
        val filteredHeader =
          headers.filter(header => header.timestamp >= fromTs && header.timestamp <= toTs)
        val searchLower = !headers.exists(_.timestamp < fromTs)
        val tmpResult   = prev ++ (filteredHeader.map(_ -> height))

        if (searchLower && height > ALF.GenesisHeight) {
          searchByTimestampHeight(height - 1, tmpResult, fromTs, toTs)
        } else {
          Right(tmpResult)
        }
      }
    }
  }
}

object BlockHeaderChain {
  def fromGenesisUnsafe(storages: Storages)(chainIndex: ChainIndex)(
      implicit config: PlatformConfig): BlockHeaderChain = {
    val genesisBlock = config.genesisBlocks(chainIndex.from.value)(chainIndex.to.value)
    val initialize   = initializeGenesis(genesisBlock.header)(_)
    createUnsafe(chainIndex, genesisBlock.header, storages, initialize)
  }

  def fromStorageUnsafe(storages: Storages)(chainIndex: ChainIndex)(
      implicit config: PlatformConfig): BlockHeaderChain = {
    val genesisBlock = config.genesisBlocks(chainIndex.from.value)(chainIndex.to.value)
    createUnsafe(chainIndex, genesisBlock.header, storages, initializeFromStorage)
  }

  def createUnsafe(
      chainIndex: ChainIndex,
      rootHeader: BlockHeader,
      storages: Storages,
      initialize: BlockHeaderChain => IOResult[Unit]
  )(implicit _config: PlatformConfig): BlockHeaderChain = {
    val headerchain = new BlockHeaderChain {
      override implicit val config    = _config
      override val headerStorage      = storages.headerStorage
      override val blockStateStorage  = storages.blockStateStorage
      override val heightIndexStorage = storages.nodeStateStorage.heightIndexStorage(chainIndex)
      override val chainStateStorage  = storages.nodeStateStorage.chainStateStorage(chainIndex)
      override val genesisHash        = rootHeader.hash

    }

    Utils.unsafe(initialize(headerchain))
    headerchain
  }

  def initializeGenesis(genesisHeader: BlockHeader)(chain: BlockHeaderChain): IOResult[Unit] = {
    chain.addGenesis(genesisHeader)
  }

  def initializeFromStorage(chain: BlockHeaderChain): IOResult[Unit] = {
    chain.loadFromStorage()
  }
}
