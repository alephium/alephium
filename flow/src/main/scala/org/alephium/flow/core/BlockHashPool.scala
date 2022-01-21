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

import org.alephium.flow.model.BlockState
import org.alephium.io.IOResult
import org.alephium.protocol.BlockHash
import org.alephium.protocol.model.Weight
import org.alephium.util.{AVector, Bytes}

trait BlockHashPool {
  def numHashes: Int

  def contains(hash: BlockHash): IOResult[Boolean]

  def containsUnsafe(hash: BlockHash): Boolean

  def getState(hash: BlockHash): IOResult[BlockState]

  def getStateUnsafe(hash: BlockHash): BlockState

  def getWeight(hash: BlockHash): IOResult[Weight]

  def getWeightUnsafe(hash: BlockHash): Weight

  def getHeight(hash: BlockHash): IOResult[Int]

  def getHeightUnsafe(hash: BlockHash): Int

  def isTip(hash: BlockHash): Boolean

  // The return excludes locator
  def getHashesAfter(locator: BlockHash): IOResult[AVector[BlockHash]]

  def getPredecessor(hash: BlockHash, height: Int): IOResult[BlockHash]

  def getBlockHashSlice(hash: BlockHash): IOResult[AVector[BlockHash]]

  // Hashes ordered by height
  def chainBackUntil(hash: BlockHash, heightUntil: Int): IOResult[AVector[BlockHash]]

  final val blockHashOrdering: Ordering[BlockHash] = { (hash0: BlockHash, hash1: BlockHash) =>
    val weight0 = getWeightUnsafe(hash0)
    val weight1 = getWeightUnsafe(hash1)
    BlockHashPool.compareWeight(hash0, weight0, hash1, weight1)
  }

  def getBestTipUnsafe(): BlockHash

  def getAllTips: AVector[BlockHash]

  def showHeight(hash: BlockHash): String = {
    val hashNum = numHashes - 1 // exclude genesis block
    val height  = getHeight(hash).getOrElse(-1)
    s"height: $height/$hashNum"
  }
}

object BlockHashPool {
  def compareWeight(hash0: BlockHash, weight0: Weight, hash1: BlockHash, weight1: Weight): Int = {
    val compare = weight0.compare(weight1)
    if (compare != 0) {
      compare
    } else {
      Bytes.byteStringOrdering.compare(hash0.bytes, hash1.bytes)
    }
  }

  def compareHeight(hash0: BlockHash, height0: Int, hash1: BlockHash, height1: Int): Int = {
    val compare = height0.compare(height1)
    if (compare != 0) {
      compare
    } else {
      Bytes.byteStringOrdering.compare(hash0.bytes, hash1.bytes)
    }
  }
}
