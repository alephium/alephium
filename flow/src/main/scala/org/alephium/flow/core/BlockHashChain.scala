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
import org.alephium.flow.core.BlockHashChain.ChainDiff
import org.alephium.flow.io.{BlockStateStorage, HeightIndexStorage}
import org.alephium.flow.model.BlockState
import org.alephium.io.{IOError, IOResult, IOUtils}
import org.alephium.protocol.{ALPH, BlockHash}
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.Weight
import org.alephium.util.{AVector, EitherF, Math, TimeStamp}

// scalastyle:off number.of.methods
trait BlockHashChain extends BlockHashPool with ChainDifficultyAdjustment with BlockHashChainState {
  implicit def brokerConfig: BrokerConfig

  def genesisHash: BlockHash

  def isGenesis(hash: BlockHash): Boolean = hash == genesisHash

  def blockStateStorage: BlockStateStorage

  def heightIndexStorage: HeightIndexStorage

  private[core] def addHash(
      hash: BlockHash,
      parentHash: BlockHash,
      height: Int,
      weight: Weight,
      timestamp: TimeStamp,
      isCanonical: Boolean
  ): IOResult[Unit] = {
    val blockState = BlockState(height, weight)
    for {
      _ <- blockStateStorage.put(hash, blockState)
      // updateHeightIndex should go after block state update to ensure that
      // all the hashes at height are contained in the chain
      _ = cacheState(hash, blockState) // cache should be updated after db
      _ <- updateHeightIndex(hash, height, isCanonical)
      _ <- updateState(hash, timestamp, parentHash)
    } yield ()
  }

  protected def addGenesis(hash: BlockHash): IOResult[Unit] = {
    assume(hash == genesisHash)
    val genesisState = BlockState(ALPH.GenesisHeight, ALPH.GenesisWeight)
    for {
      _ <- blockStateStorage.put(genesisHash, genesisState)
      _ <- updateHeightIndex(genesisHash, ALPH.GenesisHeight, true)
      _ <- setGenesisState(genesisHash, ALPH.GenesisTimestamp)
    } yield ()
  }

  protected def loadFromStorage(): IOResult[Unit] = {
    loadStateFromStorage()
  }

  @inline
  private def updateHeightIndex(
      hash: BlockHash,
      height: Int,
      isCanonical: Boolean
  ): IOResult[Unit] = {
    heightIndexStorage.getOpt(height).flatMap {
      case Some(hashes) =>
        if (isCanonical) {
          heightIndexStorage.put(height, hash +: hashes)
        } else {
          heightIndexStorage.put(height, hashes :+ hash)
        }
      case None => heightIndexStorage.put(height, AVector(hash))
    }
  }

  def getParentHash(hash: BlockHash): IOResult[BlockHash]

  def maxWeight: IOResult[Weight] =
    EitherF.foldTry(tips.keys(), Weight.zero) { (weight, hash) =>
      getWeight(hash).map(Math.max(weight, _))
    }

  // the max height is the height of the tip of max weight
  def maxHeight: IOResult[Int] = {
    IOUtils.tryExecute(maxHeightUnsafe)
  }

  def maxHeightUnsafe: Int = {
    val (maxHeight, _) =
      tips.keys().foldLeft((ALPH.GenesisHeight, ALPH.GenesisWeight)) {
        case ((height, weight), tip) =>
          getStateUnsafe(tip) match {
            case BlockState(tipHeight, tipWeight) =>
              if (tipWeight > weight) (tipHeight, tipWeight) else (height, weight)
          }
      }

    maxHeight
  }

  def isCanonical(hash: BlockHash): IOResult[Boolean] = {
    IOUtils.tryExecute(isCanonicalUnsafe(hash))
  }

  def isCanonicalUnsafe(hash: BlockHash): Boolean = {
    blockStateStorage.getOptUnsafe(hash).exists { state =>
      val hashes = getHashesUnsafe(state.height)
      hashes.headOption.contains(hash)
    }
  }

  protected[core] lazy val stateCache =
    FlowCache.states(consensusConfig.blockCacheCapacityPerChain * 4)

  def cacheState(hash: BlockHash, state: BlockState): Unit = stateCache.put(hash, state)
  def contains(hash: BlockHash): IOResult[Boolean] =
    stateCache.existsE(hash)(blockStateStorage.exists(hash))
  def containsUnsafe(hash: BlockHash): Boolean =
    stateCache.existsUnsafe(hash)(blockStateStorage.existsUnsafe(hash))
  def getState(hash: BlockHash): IOResult[BlockState] =
    stateCache.getE(hash)(blockStateStorage.get(hash))
  def getStateUnsafe(hash: BlockHash): BlockState =
    stateCache.getUnsafe(hash)(blockStateStorage.getUnsafe(hash))
  def getHeight(hash: BlockHash): IOResult[Int]    = getState(hash).map(_.height)
  def getHeightUnsafe(hash: BlockHash): Int        = getStateUnsafe(hash).height
  def getWeight(hash: BlockHash): IOResult[Weight] = getState(hash).map(_.weight)
  def getWeightUnsafe(hash: BlockHash): Weight     = getStateUnsafe(hash).weight

  def isTip(hash: BlockHash): Boolean = tips.contains(hash)

  def getHashesUnsafe(height: Int): AVector[BlockHash] = {
    heightIndexStorage.getOptUnsafe(height).getOrElse(AVector.empty)
  }

  def getHashes(height: Int): IOResult[AVector[BlockHash]] = {
    heightIndexStorage.getOpt(height).map(_.getOrElse(AVector.empty))
  }

  def getBestTipUnsafe(): BlockHash = {
    getAllTips.max(blockHashOrdering)
  }

  def getBestTip(): IOResult[BlockHash] = {
    IOUtils.tryExecute(getBestTipUnsafe())
  }

  def getAllTips: AVector[BlockHash] = {
    AVector.from(tips.keys())
  }

  private def getLink(hash: BlockHash): IOResult[BlockHashChain.Link] = {
    getParentHash(hash).map(BlockHashChain.Link(_, hash))
  }

  def getHashesAfter(locator: BlockHash): IOResult[AVector[BlockHash]] = {
    contains(locator).flatMap {
      case false => Right(AVector.empty)
      case true =>
        for {
          height <- getHeight(locator)
          hashes <- getHashes(height + 1)
          links  <- hashes.mapE(getLink)
          all    <- getHashesAfter(height + 1, links.filter(_.parentHash == locator).map(_.hash))
        } yield all
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def getHashesAfter(
      height: Int,
      hashes: AVector[BlockHash]
  ): IOResult[AVector[BlockHash]] = {
    if (hashes.isEmpty) {
      Right(AVector.empty)
    } else {
      for {
        childHashes <- getHashes(height + 1)
        childPairs  <- childHashes.mapE(getLink)
        validChildHashes = childPairs.filter(p => hashes.contains(p.parentHash)).map(_.hash)
        rest <- getHashesAfter(height + 1, validChildHashes)
      } yield hashes ++ rest
    }
  }

  def getPredecessor(hash: BlockHash, height: Int): IOResult[BlockHash] = {
    assume(height >= ALPH.GenesisHeight)
    @tailrec
    def iter(currentHash: BlockHash, currentHeight: Int): IOResult[BlockHash] = {
      if (currentHeight == height) {
        Right(currentHash)
      } else {
        getParentHash(currentHash) match {
          case Right(parentHash) => iter(parentHash, currentHeight - 1)
          case Left(error)       => Left(error)
        }
      }
    }

    getHeight(hash).flatMap(iter(hash, _))
  }

  // If oldHash is an ancestor of newHash, it returns all the new hashes after oldHash to newHash (inclusive)
  def getBlockHashesBetween(
      newHash: BlockHash,
      oldHash: BlockHash
  ): IOResult[AVector[BlockHash]] = {
    for {
      newHeight <- getHeight(newHash)
      oldHeight <- getHeight(oldHash)
      result    <- getBlockHashesBetween(newHash, newHeight, oldHash, oldHeight)
    } yield result
  }

  def getBlockHashesBetween(
      newHash: BlockHash,
      newHeight: Int,
      oldHash: BlockHash,
      oldHeight: Int
  ): IOResult[AVector[BlockHash]] = {
    assume(oldHeight >= ALPH.GenesisHeight)
    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def iter(
        acc: AVector[BlockHash],
        currentHash: BlockHash,
        currentHeight: Int
    ): IOResult[AVector[BlockHash]] = {
      if (currentHeight > oldHeight) {
        getParentHash(currentHash).flatMap(iter(acc :+ currentHash, _, currentHeight - 1))
      } else if (currentHeight == oldHeight && currentHash == oldHash) {
        Right(acc)
      } else {
        val error = new RuntimeException(
          s"Cannot calculate the hashes between new ${newHash.shortHex} and old ${oldHash.shortHex}"
        )
        Left(IOError.Other(error))
      }
    }

    iter(AVector.empty, newHash, newHeight).map(_.reverse)
  }

  def getBlockHashSlice(hash: BlockHash): IOResult[AVector[BlockHash]] = {
    @tailrec
    def iter(acc: AVector[BlockHash], current: BlockHash): IOResult[AVector[BlockHash]] = {
      if (isGenesis(current)) {
        Right(acc :+ current)
      } else {
        getParentHash(current) match {
          case Right(parentHash) => iter(acc :+ current, parentHash)
          case Left(error)       => Left(error)
        }
      }
    }

    iter(AVector.empty, hash).map(_.reverse)
  }

  def isBeforeUnsafe(hash1: BlockHash, hash2: BlockHash): Boolean = {
    Utils.unsafe(isBefore(hash1, hash2))
  }

  def isBefore(hash1: BlockHash, hash2: BlockHash): IOResult[Boolean] = {
    for {
      height1 <- getHeight(hash1)
      height2 <- getHeight(hash2)
      result  <- isBefore(hash1, height1, hash2, height2)
    } yield result
  }

  private def isBefore(
      hash1: BlockHash,
      height1: Int,
      hash2: BlockHash,
      height2: Int
  ): IOResult[Boolean] = {
    if (height1 < height2) {
      getPredecessor(hash2, height1).map(_.equals(hash1))
    } else if (height1 == height2) {
      Right(hash1.equals(hash2))
    } else {
      Right(false)
    }
  }

  def calHashDiff(newHash: BlockHash, oldHash: BlockHash): IOResult[ChainDiff] = {
    for {
      newHeight <- getHeight(newHash)
      oldHeight <- getHeight(oldHash)
      heightUntil = math.min(newHeight, oldHeight) - 1 // h - 1 to include earlier one
      newBack <- chainBackUntil(newHash, heightUntil)
      oldBack <- chainBackUntil(oldHash, heightUntil)
      diff    <- calHashDiffFromSameHeight(newBack.head, oldBack.head)
    } yield {
      ChainDiff(oldBack.tail.reverse ++ diff.toRemove.reverse, diff.toAdd ++ newBack.tail)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def calHashDiffFromSameHeight(
      newHash: BlockHash,
      oldHash: BlockHash
  ): IOResult[ChainDiff] = {
    if (newHash == oldHash) {
      Right(ChainDiff(AVector.empty, AVector.empty))
    } else {
      for {
        newParent <- getParentHash(newHash)
        oldParent <- getParentHash(oldHash)
        diff      <- calHashDiffFromSameHeight(newParent, oldParent)
      } yield ChainDiff(diff.toRemove :+ oldHash, diff.toAdd :+ newHash)
    }
  }

  def isRecentHeight(height: Int): IOResult[Boolean] = {
    maxHeight.map(height >= _ - consensusConfig.recentBlockHeightDiff)
  }
}
// scalastyle:on number.of.methods

object BlockHashChain {
  final case class ChainDiff(toRemove: AVector[BlockHash], toAdd: AVector[BlockHash])

  final case class Link(parentHash: BlockHash, hash: BlockHash)

  final case class State(numHashes: Int, tips: AVector[BlockHash])

  object State {
    import org.alephium.serde._
    implicit val serde: Serde[State] = Serde.forProduct2(State(_, _), t => (t.numHashes, t.tips))
  }
}
