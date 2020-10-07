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

import org.alephium.flow.core.BlockHashChain.ChainDiff
import org.alephium.flow.io.{BlockStateStorage, HeightIndexStorage}
import org.alephium.flow.model.BlockState
import org.alephium.io.{IOError, IOResult}
import org.alephium.protocol.{ALF, Hash}
import org.alephium.protocol.config.BrokerConfig
import org.alephium.util.{AVector, EitherF, TimeStamp}

// scalastyle:off number.of.methods
trait BlockHashChain extends BlockHashPool with ChainDifficultyAdjustment with BlockHashChainState {
  implicit def brokerConfig: BrokerConfig

  def genesisHash: Hash

  def isGenesis(hash: Hash): Boolean = hash == genesisHash

  def blockStateStorage: BlockStateStorage

  def heightIndexStorage: HeightIndexStorage

  protected def addHash(hash: Hash,
                        parentHash: Hash,
                        height: Int,
                        weight: BigInt,
                        chainWeight: BigInt,
                        timestamp: TimeStamp,
                        isCanonical: Boolean): IOResult[Unit] = {
    for {
      _ <- blockStateStorage.put(hash, BlockState(height, weight, chainWeight))
      _ <- updateHeightIndex(hash, height, isCanonical)
      _ <- updateState(hash, timestamp, parentHash)
    } yield ()
  }

  protected def addGenesis(hash: Hash): IOResult[Unit] = {
    assume(hash == genesisHash)
    val genesisState = BlockState(ALF.GenesisHeight, ALF.GenesisWeight, ALF.GenesisWeight)
    for {
      _ <- blockStateStorage.put(genesisHash, genesisState)
      _ <- updateHeightIndex(genesisHash, ALF.GenesisHeight, true)
      _ <- setGenesisState(genesisHash, ALF.GenesisTimestamp)
    } yield ()
  }

  protected def loadFromStorage(): IOResult[Unit] = {
    loadStateFromStorage()
  }

  @inline
  private def updateHeightIndex(hash: Hash, height: Int, isCanonical: Boolean): IOResult[Unit] = {
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

  def getParentHash(hash: Hash): IOResult[Hash]

  def maxWeight: IOResult[BigInt] = EitherF.foldTry(tips.keys, BigInt(0)) { (weight, hash) =>
    getWeight(hash).map(weight.max)
  }

  def maxChainWeight: IOResult[BigInt] = EitherF.foldTry(tips.keys, BigInt(0)) {
    (chainWeight, hash) =>
      getChainWeight(hash).map(chainWeight.max)
  }

  def maxHeight: IOResult[Int] = EitherF.foldTry(tips.keys, ALF.GenesisHeight) { (height, hash) =>
    getHeight(hash).map(math.max(height, _))
  }

  def maxHeightUnsafe: Int = tips.keys.foldLeft(ALF.GenesisHeight) { (height, hash) =>
    math.max(getHeightUnsafe(hash), height)
  }

  def isCanonicalUnsafe(hash: Hash): Boolean = {
    blockStateStorage.getOptUnsafe(hash).exists { state =>
      val hashes = getHashesUnsafe(state.height)
      hashes.head == hash // .head is safe here
    }
  }

  def contains(hash: Hash): IOResult[Boolean]      = blockStateStorage.exists(hash)
  def containsUnsafe(hash: Hash): Boolean          = blockStateStorage.existsUnsafe(hash)
  def getState(hash: Hash): IOResult[BlockState]   = blockStateStorage.get(hash)
  def getStateUnsafe(hash: Hash): BlockState       = blockStateStorage.getUnsafe(hash)
  def getHeight(hash: Hash): IOResult[Int]         = blockStateStorage.get(hash).map(_.height)
  def getHeightUnsafe(hash: Hash): Int             = blockStateStorage.getUnsafe(hash).height
  def getWeight(hash: Hash): IOResult[BigInt]      = blockStateStorage.get(hash).map(_.weight)
  def getWeightUnsafe(hash: Hash): BigInt          = blockStateStorage.getUnsafe(hash).weight
  def getChainWeight(hash: Hash): IOResult[BigInt] = blockStateStorage.get(hash).map(_.chainWeight)
  def getChainWeightUnsafe(hash: Hash): BigInt     = blockStateStorage.getUnsafe(hash).chainWeight

  def isTip(hash: Hash): Boolean = tips.contains(hash)

  def getHashesUnsafe(height: Int): AVector[Hash] = {
    heightIndexStorage.getOptUnsafe(height).getOrElse(AVector.empty)
  }

  def getHashes(height: Int): IOResult[AVector[Hash]] = {
    heightIndexStorage.getOpt(height).map(_.getOrElse(AVector.empty))
  }

  def getBestTipUnsafe: Hash = {
    assume(tips.size != 0)
    val weighted = getAllTips.map { hash =>
      hash -> getWeightUnsafe(hash)
    }
    weighted.maxBy(_._2)._1
  }

  def getAllTips: AVector[Hash] = {
    AVector.from(tips.keys)
  }

  private def getLink(hash: Hash): IOResult[BlockHashChain.Link] = {
    getParentHash(hash).map(BlockHashChain.Link(_, hash))
  }

  def getHashesAfter(locator: Hash): IOResult[AVector[Hash]] = {
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
  private def getHashesAfter(height: Int, hashes: AVector[Hash]): IOResult[AVector[Hash]] = {
    if (hashes.isEmpty) Right(AVector.empty)
    else {
      for {
        childHashes <- getHashes(height + 1)
        childPairs  <- childHashes.mapE(getLink)
        validChildHashes = childPairs.filter(p => hashes.contains(p.parentHash)).map(_.hash)
        rest <- getHashesAfter(height + 1, validChildHashes)
      } yield hashes ++ rest
    }
  }

  def getPredecessor(hash: Hash, height: Int): IOResult[Hash] = {
    assume(height >= ALF.GenesisHeight)
    @tailrec
    def iter(currentHash: Hash, currentHeight: Int): IOResult[Hash] = {
      if (currentHeight == height) Right(currentHash)
      else {
        getParentHash(currentHash) match {
          case Right(parentHash) => iter(parentHash, currentHeight - 1)
          case Left(error)       => Left(error)
        }
      }
    }

    getHeight(hash).flatMap(iter(hash, _))
  }

  // If oldHash is an ancestor of newHash, it returns all the new hashes after oldHash to newHash (inclusive)
  def getBlockHashesBetween(newHash: Hash, oldHash: Hash): IOResult[AVector[Hash]] = {
    for {
      newHeight <- getHeight(newHash)
      oldHeight <- getHeight(oldHash)
      result    <- getBlockHashesBetween(newHash, newHeight, oldHash, oldHeight)
    } yield result
  }

  def getBlockHashesBetween(newHash: Hash,
                            newHeight: Int,
                            oldHash: Hash,
                            oldHeight: Int): IOResult[AVector[Hash]] = {
    assume(oldHeight >= ALF.GenesisHeight)
    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def iter(acc: AVector[Hash], currentHash: Hash, currentHeight: Int): IOResult[AVector[Hash]] = {
      if (currentHeight > oldHeight) {
        getParentHash(currentHash).flatMap(iter(acc :+ currentHash, _, currentHeight - 1))
      } else if (currentHeight == oldHeight && currentHash == oldHash) {
        Right(acc)
      } else {
        val error = new RuntimeException(
          s"Cannot calculate the hashes between new ${newHash.shortHex} and old ${oldHash.shortHex}")
        Left(IOError.Other(error))
      }
    }

    iter(AVector.empty, newHash, newHeight).map(_.reverse)
  }

  def getBlockHashSlice(hash: Hash): IOResult[AVector[Hash]] = {
    @tailrec
    def iter(acc: AVector[Hash], current: Hash): IOResult[AVector[Hash]] = {
      if (isGenesis(current)) Right(acc :+ current)
      else {
        getParentHash(current) match {
          case Right(parentHash) => iter(acc :+ current, parentHash)
          case Left(error)       => Left(error)
        }
      }
    }

    iter(AVector.empty, hash).map(_.reverse)
  }

  def isBefore(hash1: Hash, hash2: Hash): IOResult[Boolean] = {
    for {
      height1 <- getHeight(hash1)
      height2 <- getHeight(hash2)
      result  <- isBefore(hash1, height1, hash2, height2)
    } yield result
  }

  private def isBefore(hash1: Hash, height1: Int, hash2: Hash, height2: Int): IOResult[Boolean] = {
    if (height1 < height2) {
      getPredecessor(hash2, height1).map(_.equals(hash1))
    } else if (height1 == height2) {
      Right(hash1.equals(hash2))
    } else Right(false)
  }

  def calHashDiff(newHash: Hash, oldHash: Hash): IOResult[ChainDiff] = {
    for {
      newHeight <- getHeight(newHash)
      oldHeight <- getHeight(oldHash)
      heightUntil = math.min(newHeight, oldHeight) - 1 // h - 1 to include earlier one
      newBack <- chainBack(newHash, heightUntil)
      oldBack <- chainBack(oldHash, heightUntil)
      diff    <- calHashDiffFromSameHeight(newBack.head, oldBack.head)
    } yield {
      ChainDiff(oldBack.tail.reverse ++ diff.toRemove.reverse, diff.toAdd ++ newBack.tail)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def calHashDiffFromSameHeight(newHash: Hash, oldHash: Hash): IOResult[ChainDiff] = {
    if (newHash == oldHash) Right(ChainDiff(AVector.empty, AVector.empty))
    else {
      for {
        newParent <- getParentHash(newHash)
        oldParent <- getParentHash(oldHash)
        diff      <- calHashDiffFromSameHeight(newParent, oldParent)
      } yield ChainDiff(diff.toRemove :+ oldHash, diff.toAdd :+ newHash)
    }
  }

  def isRecent(hash: Hash): IOResult[Boolean] = {
    getHeight(hash).flatMap(isRecent)
  }

  def isRecent(height: Int): IOResult[Boolean] = {
    maxHeight.map(height >= _ - consensusConfig.recentBlockHeightDiff)
  }
}
// scalastyle:on number.of.methods

object BlockHashChain {
  final case class ChainDiff(toRemove: AVector[Hash], toAdd: AVector[Hash])

  final case class Link(parentHash: Hash, hash: Hash)

  final case class State(numHashes: Int, tips: AVector[Hash])

  object State {
    import org.alephium.serde._
    implicit val serde: Serde[State] = Serde.forProduct2(State(_, _), t => (t.numHashes, t.tips))
  }
}
