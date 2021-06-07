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

import scala.annotation.tailrec

import org.alephium.flow.Utils
import org.alephium.flow.io._
import org.alephium.flow.setting.ConsensusSetting
import org.alephium.io.{IOError, IOResult}
import org.alephium.protocol.{ALF, BlockHash}
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{BlockHeader, Target, Weight}
import org.alephium.util.{AVector, EitherF, LruCache, TimeStamp}

trait BlockHeaderChain extends BlockHeaderPool with BlockHashChain {
  def headerStorage: BlockHeaderStorage

  lazy val headerCache =
    LruCache[BlockHash, BlockHeader, IOError](consensusConfig.blockCacheCapacityPerChain)

  def getBlockHeader(hash: BlockHash): IOResult[BlockHeader] = {
    headerCache.get(hash)(headerStorage.get(hash))
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

    for {
      parentState <- getState(parentHash)
      height = parentState.height + 1
      _           <- addHeader(header)
      isCanonical <- checkCanonicality(header.hash, weight)
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

  private def checkCanonicality(hash: BlockHash, weight: Weight): IOResult[Boolean] = {
    EitherF.forallTry(tips.keys) { tip =>
      getWeight(tip).map(BlockHashPool.compare(hash, weight, tip, _) > 0)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  final def reorgFrom(hash: BlockHash, height: Int): IOResult[Unit] = {
    getHashes(height).flatMap { hashes =>
      assume(hashes.contains(hash))
      if (hashes.head == hash) {
        Right(())
      } else {
        for {
          _      <- heightIndexStorage.put(height, hash +: hashes.filter(_ != hash))
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

  protected def addHeaderUnsafe(header: BlockHeader): Unit = {
    headerStorage.putUnsafe(header)
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def chainBack(hash: BlockHash, heightUntil: Int): IOResult[AVector[BlockHash]] = {
    getHeight(hash).flatMap {
      case height if height > heightUntil =>
        getBlockHeader(hash).flatMap { header =>
          if (height > heightUntil + 1) {
            chainBack(header.parentHash, heightUntil).map(_ :+ hash)
          } else {
            Right(AVector(hash))
          }
        }
      case _ => Right(AVector.empty)
    }
  }

  def getHashTarget(hash: BlockHash): IOResult[Target] = {
    for {
      header    <- getBlockHeader(hash)
      newTarget <- calHashTarget(hash, header.target)
    } yield newTarget
  }

  def getHeightedBlockHeaders(
      fromTs: TimeStamp,
      toTs: TimeStamp
  ): IOResult[AVector[(BlockHeader, Int)]] =
    for {
      height <- maxHeight
      result <- searchByTimestampHeight(height, AVector.empty, fromTs, toTs)
    } yield result

  //TODO Make it tailrec
  //TODO Use binary search with the height params to find quicklier all our blocks.
  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def searchByTimestampHeight(
      height: Int,
      prev: AVector[(BlockHeader, Int)],
      fromTs: TimeStamp,
      toTs: TimeStamp
  ): IOResult[AVector[(BlockHeader, Int)]] = {
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

  def getSyncDataUnsafe(locators: AVector[BlockHash]): AVector[BlockHash] = {
    val reversed           = locators.reverse
    val lastCanonicalIndex = reversed.indexWhere(isCanonicalUnsafe)
    if (lastCanonicalIndex == -1) {
      AVector.empty // nothing in common
    } else {
      val lastCanonicalHash = reversed(lastCanonicalIndex)
      val heightFrom        = getHeightUnsafe(lastCanonicalHash) + 1
      val heightTo          = math.min(heightFrom + maxSyncBlocksPerChain, maxHeightUnsafe)
      if (Utils.unsafe(isRecent(heightFrom))) {
        getRecentDataUnsafe(heightFrom, heightTo)
      } else {
        getSyncDataUnsafe(heightFrom, heightTo)
      }
    }
  }

  // heightFrom is exclusive, heightTo is inclusive
  def getSyncDataUnsafe(heightFrom: Int, heightTo: Int): AVector[BlockHash] = {
    @tailrec
    def iter(
        currentHeader: BlockHeader,
        currentHeight: Int,
        acc: AVector[BlockHash]
    ): AVector[BlockHash] = {
      if (currentHeight <= heightFrom) {
        acc :+ currentHeader.hash
      } else {
        val parentHeader = getBlockHeaderUnsafe(currentHeader.parentHash)
        iter(parentHeader, currentHeight - 1, acc :+ currentHeader.hash)
      }
    }

    val startHeader = Utils.unsafe(getHashes(heightTo).map(_.head).flatMap(getBlockHeader))
    iter(startHeader, heightTo, AVector.empty).reverse
  }

  def getRecentDataUnsafe(heightFrom: Int, heightTo: Int): AVector[BlockHash] = {
    AVector.from(heightFrom to heightTo).flatMap(getHashesUnsafe)
  }
}

object BlockHeaderChain {
  def fromGenesisUnsafe(storages: Storages)(
      genesisHeader: BlockHeader
  )(implicit brokerConfig: BrokerConfig, consensusSetting: ConsensusSetting): BlockHeaderChain = {
    val initialize = initializeGenesis(genesisHeader)(_)
    createUnsafe(genesisHeader, storages, initialize)
  }

  def fromStorageUnsafe(storages: Storages)(
      genesisHeader: BlockHeader
  )(implicit brokerConfig: BrokerConfig, consensusSetting: ConsensusSetting): BlockHeaderChain = {
    createUnsafe(genesisHeader, storages, initializeFromStorage)
  }

  def createUnsafe(
      rootHeader: BlockHeader,
      storages: Storages,
      initialize: BlockHeaderChain => IOResult[Unit]
  )(implicit _brokerConfig: BrokerConfig, _consensusSetting: ConsensusSetting): BlockHeaderChain = {
    val headerchain = new BlockHeaderChain {
      override val brokerConfig      = _brokerConfig
      override val consensusConfig   = _consensusSetting
      override val headerStorage     = storages.headerStorage
      override val blockStateStorage = storages.blockStateStorage
      override val heightIndexStorage =
        storages.nodeStateStorage.heightIndexStorage(rootHeader.chainIndex)
      override val chainStateStorage =
        storages.nodeStateStorage.chainStateStorage(rootHeader.chainIndex)
      override val genesisHash = rootHeader.hash
      override val chainIndex  = rootHeader.chainIndex
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
