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

import org.alephium.io.IOResult
import org.alephium.protocol.model.{Block, BlockHash, Weight}
import org.alephium.util.AVector

trait BlockPool extends BlockHashPool {

  def contains(block: Block): IOResult[Boolean] = contains(block.hash)

  // Assuming the hash is in the pool
  def getBlock(hash: BlockHash): IOResult[Block]

  // Assuming the block is verified
  def add(block: Block, weight: Weight): IOResult[Unit]

  def getBlocksAfter(locator: BlockHash): IOResult[AVector[Block]] = {
    for {
      hashes <- getHashesAfter(locator)
      blocks <- hashes.mapE(getBlock)
    } yield blocks
  }

  def getHeight(block: Block): IOResult[Int] = getHeight(block.hash)

  def getWeight(block: Block): IOResult[Weight] = getWeight(block.hash)

  // TODO: use ChainSlice instead of AVector[Block]
  def getBlockSlice(hash: BlockHash): IOResult[AVector[Block]] = {
    for {
      hashes <- getBlockHashSlice(hash)
      blocks <- hashes.mapE(getBlock)
    } yield blocks
  }
  def getBlockSlice(block: Block): IOResult[AVector[Block]] = getBlockSlice(block.hash)

  def isTip(block: Block): Boolean = isTip(block.hash)
}
