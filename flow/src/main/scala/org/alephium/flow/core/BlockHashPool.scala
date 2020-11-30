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
import org.alephium.protocol.Hash
import org.alephium.util.AVector

trait BlockHashPool {
  def numHashes: Int

  def contains(hash: Hash): IOResult[Boolean]

  def containsUnsafe(hash: Hash): Boolean

  def getState(hash: Hash): IOResult[BlockState]

  def getStateUnsafe(hash: Hash): BlockState

  def getWeight(hash: Hash): IOResult[BigInt]

  def getWeightUnsafe(hash: Hash): BigInt

  def getChainWeight(hash: Hash): IOResult[BigInt]

  def getChainWeightUnsafe(hash: Hash): BigInt

  def getHeight(hash: Hash): IOResult[Int]

  def getHeightUnsafe(hash: Hash): Int

  def isTip(hash: Hash): Boolean

  // The return excludes locator
  def getHashesAfter(locator: Hash): IOResult[AVector[Hash]]

  def getPredecessor(hash: Hash, height: Int): IOResult[Hash]

  def getBlockHashSlice(hash: Hash): IOResult[AVector[Hash]]

  // Hashes ordered by height
  def chainBack(hash: Hash, heightUntil: Int): IOResult[AVector[Hash]]

  def getBestTipUnsafe: Hash

  def getAllTips: AVector[Hash]

  def show(hash: Hash): String = {
    val shortHash = hash.shortHex
    val hashNum   = numHashes - 1 // exclude genesis block
    val height    = getHeight(hash).getOrElse(-1)
    s"hash: $shortHash; height: $height/$hashNum"
  }
}
