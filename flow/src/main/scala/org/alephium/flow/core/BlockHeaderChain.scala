// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.flow.core

import com.typesafe.scalalogging.LazyLogging

import org.alephium.flow.Utils
import org.alephium.flow.io._
import org.alephium.flow.setting.{ConsensusSetting, ConsensusSettings}
import org.alephium.io.{IOError, IOResult, IOUtils}
import org.alephium.protocol.ALPH
import org.alephium.protocol.config.{BrokerConfig, NetworkConfig}
import org.alephium.protocol.model.{BlockHash, BlockHeader, ChainIndex, Target, Weight}
import org.alephium.protocol.vm.BlockEnv
import org.alephium.util._

trait BlockHeaderChain extends BlockHeaderPool with BlockHashChain with LazyLogging {
  implicit def networkConfig: NetworkConfig

  def headerStorage: BlockHeaderStorage

  lazy val headerCache = FlowCache.headers(consensusConfigs.blockCacheCapacityPerChain * 4)

  def cacheHeader(header: BlockHeader): Unit = {
    headerCache.put(header.hash, header)
  }

  def getBlockHeader(hash: BlockHash): IOResult[BlockHeader] = {
    headerCache.getE(hash) {
      headerStorage.get(hash)
    }
  }

  def getBlockHeaderUnsafe(hash: BlockHash): BlockHeader = {
    headerCache.getUnsafe(hash)(headerStorage.getUnsafe(hash))
  }

  def getParentHash(hash: BlockHash): IOResult[BlockHash] = {
    getBlockHeader(hash).map(_.parentHash)
  }

  def getTimestamp(hash: BlockHash): IOResult[TimeStamp] = {
    getBlockHeader(hash).map(_.timestamp)
  }

  def getTimestampUnsafe(hash: BlockHash): TimeStamp = {
    getBlockHeaderUnsafe(hash).timestamp
  }

  def getTarget(hash: BlockHash): IOResult[Target] = {
    getBlockHeader(hash).map(_.target)
  }

  def getTarget(height: Int): IOResult[Target] = {
    getHashes(height).flatMap { hashes =>
      hashes.headOption match {
        case Some(head) => getTarget(head)
        case None       => Left(IOError.Other(new RuntimeException(s"No block for height $height")))
      }
    }
  }

  def add(header: BlockHeader, weight: Weight): IOResult[Unit] = {
    assume(!header.isGenesis)
    val parentHash = header.parentHash
    assume {
      val assertion = for {
        bool1 <- contains(header.hash)
        bool2 <- contains(parentHash)
      } yield !bool1 && bool2
      assertion.getOrElse(false)
    }

    cacheHeader(header)
    for {
      parentState <- getState(parentHash)
      height = parentState.height + 1
      _           <- addHeader(header)
      isCanonical <- checkCanonicality(header.hash, height)
      _           <- addHash(header.hash, parentHash, height, weight, header.timestamp, isCanonical)
      _           <- if (isCanonical) reorgFrom(header.parentHash, height - 1) else Right(())
    } yield ()
  }

  protected def addGenesis(header: BlockHeader): IOResult[Unit] = {
    assume(header.hash == genesisHash)
    for {
      _ <- addHeader(header)
      _ <- addGenesis(header.hash)
    } yield ()
  }

  override def checkCompletenessUnsafe(hash: BlockHash): Boolean = {
    checkCompletenessHelper(
      hash,
      hash => headerStorage.existsUnsafe(hash) && super.checkCompletenessUnsafe(hash),
      _ => true
    )
  }

  def checkCompletenessHelper(
      hash: BlockHash,
      checkSelf: BlockHash => Boolean,
      checkSuper: BlockHash => Boolean
  ): Boolean = {
    checkSelf(hash) && checkSuper(hash) && {
      var toCheckHeader = getBlockHeaderUnsafe(hash)
      (0 until 10).forall { _ =>
        if (toCheckHeader.isGenesis) {
          true
        } else {
          val toCheckHash = toCheckHeader.hash
          toCheckHeader = getBlockHeaderUnsafe(toCheckHeader.parentHash)
          checkSelf(toCheckHash)
        }
      }
    }
  }

  def cleanTips(): Unit = {
    val currentTips = getAllTips
    val invalidTips = currentTips.filter(!checkCompletenessUnsafe(_))
    if (currentTips.length > invalidTips.length) {
      invalidTips.foreach(removeInvalidTip)
    } else {
      // TODO: recover valid tips
    }
  }

  // We use height for canonicality checking. This will converge to weight based checking eventually
  private def checkCanonicality(hash: BlockHash, height: Int): IOResult[Boolean] = {
    EitherF.forallTry(tips.keys()) { tip =>
      getHeight(tip).map(BlockHashPool.compareHeight(hash, height, tip, _) > 0)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  final def reorgFrom(hash: BlockHash, height: Int): IOResult[Unit] = {
    getHashes(height).flatMap { hashes =>
      assume(hashes.contains(hash))
      if (hashes.head == hash) {
        Right(())
      } else {
        val blockHashes = hash +: hashes.filter(_ != hash)
        for {
          _ <- heightIndexStorage
            .put(height, blockHashes)
            .map(_ => cacheHashes(height, blockHashes))
          parent <- getParentHash(hash)
          _      <- reorgFrom(parent, height - 1)
        } yield ()
      }
    }
  }

  override protected def loadFromStorage(): IOResult[Unit] = {
    super.loadFromStorage()
  }

  protected def addHeader(header: BlockHeader): IOResult[Unit] = {
    headerStorage.put(header)
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def chainBackUntil(hash: BlockHash, heightUntil: Int): IOResult[AVector[BlockHash]] = {
    getHeight(hash).flatMap {
      case height if height > heightUntil =>
        getBlockHeader(hash).flatMap { header =>
          if (height > heightUntil + 1) {
            chainBackUntil(header.parentHash, heightUntil).map(_ :+ hash)
          } else {
            Right(AVector(hash))
          }
        }
      case _ => Right(AVector.empty)
    }
  }

  def getNextHashTargetRaw(hash: BlockHash, nextTimeStamp: TimeStamp): IOResult[Target] = {
    implicit val consensusConfig: ConsensusSetting =
      consensusConfigs.getConsensusConfig(nextTimeStamp)
    for {
      header    <- getBlockHeader(hash)
      newTarget <- calNextHashTargetRaw(hash, header.target, header.timestamp, nextTimeStamp)
    } yield newTarget
  }

  def getDryrunBlockEnv(): IOResult[BlockEnv] = {
    val now = TimeStamp.now()
    for {
      tip    <- getBestTip()
      target <- getNextHashTargetRaw(tip, now)
    } yield BlockEnv(chainIndex, networkConfig.networkId, now, target, Some(tip))
  }

  def getBlockTime(header: BlockHeader): IOResult[Duration] = {
    getBlockHeader(header.parentHash)
      .map(header.timestamp deltaUnsafe _.timestamp)
  }

  def checkHashIndexingUnsafe(): Unit = {
    val maxHeight   = maxHeightByWeightUnsafe
    var startHeight = maxHeight - maxForkDepth

    if (startHeight > ALPH.GenesisHeight) {
      while (getHashesUnsafe(startHeight).length > 1) {
        startHeight = startHeight - 1
      }
      checkAndRepairHashIndexingUnsafe(startHeight)
      logger.info(s"Start checking hash indexing for $chainIndex from height $startHeight")
    } else {
      logger.info(s"No need to check hash indexing for $chainIndex due to too few blocks")
    }
  }

  def checkAndRepairHashIndexingUnsafe(startHeight: Int): Unit = {
    val startHash  = getHashesUnsafe(startHeight).head
    val chainIndex = ChainIndex.from(startHash)

    var currentHeight = startHeight
    var currentHeader = getBlockHeaderUnsafe(startHash)
    while (!currentHeader.isGenesis) {
      val nextHeight = currentHeight - 1
      val nextHash   = currentHeader.parentHash
      val nextHashes = getHashesUnsafe(nextHeight)
      if (nextHashes.head != nextHash) {
        logger.warn(s"Update hashes order at: chainIndex $chainIndex; height $nextHeight")
        val blockHashes = nextHash +: nextHashes.filter(_ != nextHash)
        heightIndexStorage.put(nextHeight, blockHashes)
        // Update cache if the height is cached
        updateHashesCache(nextHeight, blockHashes)
      }

      currentHeight = nextHeight
      currentHeader = getBlockHeaderUnsafe(nextHash)
    }
  }

  def getHeadersByHeights(heights: AVector[Int]): IOResult[AVector[BlockHeader]] = {
    IOUtils.tryExecute(getHeadersByHeightsUnsafe(heights))
  }

  def getHeadersByHeightsUnsafe(heights: AVector[Int]): AVector[BlockHeader] = {
    heights.fold(AVector.ofCapacity[BlockHeader](heights.length)) { case (acc, height) =>
      getHashesUnsafe(height).headOption match {
        case Some(hash) => acc :+ getBlockHeaderUnsafe(hash)
        case None       => acc
      }
    }
  }
}

object BlockHeaderChain {
  def fromGenesisUnsafe(storages: Storages)(
      genesisHeader: BlockHeader
  )(implicit
      brokerConfig: BrokerConfig,
      networkConfig: NetworkConfig,
      consensusSettings: ConsensusSettings
  ): BlockHeaderChain = {
    val initialize = initializeGenesis(genesisHeader)(_)
    createUnsafe(genesisHeader, storages, initialize)
  }

  def fromStorageUnsafe(storages: Storages)(
      genesisHeader: BlockHeader
  )(implicit
      brokerConfig: BrokerConfig,
      networkConfig: NetworkConfig,
      consensusSettings: ConsensusSettings
  ): BlockHeaderChain = {
    createUnsafe(genesisHeader, storages, initializeFromStorage)
  }

  def createUnsafe(
      rootHeader: BlockHeader,
      storages: Storages,
      initialize: BlockHeaderChain => IOResult[Unit]
  )(implicit
      _brokerConfig: BrokerConfig,
      _networkConfig: NetworkConfig,
      _consensusSettings: ConsensusSettings
  ): BlockHeaderChain = {
    val headerchain = new BlockHeaderChain {
      override val brokerConfig      = _brokerConfig
      override val networkConfig     = _networkConfig
      override val consensusConfigs  = _consensusSettings
      override val headerStorage     = storages.headerStorage
      override val blockStateStorage = storages.blockStateStorage
      override val heightIndexStorage =
        storages.nodeStateStorage.heightIndexStorage(rootHeader.chainIndex)
      override val chainStateStorage =
        storages.nodeStateStorage.chainStateStorage(rootHeader.chainIndex)
      override val genesisHash = rootHeader.hash
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
