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
import org.alephium.util.AVector

trait BlockHashPool {
  def numHashes: Int

  def contains(hash: BlockHash): IOResult[Boolean]

  def containsUnsafe(hash: BlockHash): Boolean

  def getState(hash: BlockHash): IOResult[BlockState]

  def getStateUnsafe(hash: BlockHash): BlockState

  def getWeight(hash: BlockHash): IOResult[BigInt]

  def getWeightUnsafe(hash: BlockHash): BigInt

  def getChainWeight(hash: BlockHash): IOResult[BigInt]

  def getChainWeightUnsafe(hash: BlockHash): BigInt

  def getHeight(hash: BlockHash): IOResult[Int]

  def getHeightUnsafe(hash: BlockHash): Int

  def isTip(hash: BlockHash): Boolean

  // The return excludes locator
  def getHashesAfter(locator: BlockHash): IOResult[AVector[BlockHash]]

  def getPredecessor(hash: BlockHash, height: Int): IOResult[BlockHash]

  def getBlockHashSlice(hash: BlockHash): IOResult[AVector[BlockHash]]

  // Hashes ordered by height
  def chainBack(hash: BlockHash, heightUntil: Int): IOResult[AVector[BlockHash]]

  def getBestTipUnsafe: BlockHash

  def getAllTips: AVector[BlockHash]

  def show(hash: BlockHash): String = {
    val shortHash = hash.shortHex
    val hashNum   = numHashes - 1 // exclude genesis block
    val height    = getHeight(hash).getOrElse(-1)
    s"hash: $shortHash; height: $height/$hashNum"
  }
}
