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
import org.alephium.protocol.model.{BlockHash, BlockHeader, Weight}
import org.alephium.util.AVector

trait BlockHeaderPool extends BlockHashPool {

  def contains(bh: BlockHeader): IOResult[Boolean] = contains(bh.hash)

  // Assuming the entity is in the pool
  def getBlockHeader(hash: BlockHash): IOResult[BlockHeader]
  def getBlockHeaderUnsafe(hash: BlockHash): BlockHeader

  def add(header: BlockHeader, weight: Weight): IOResult[Unit]

  def getHeadersAfter(locator: BlockHash): IOResult[AVector[BlockHeader]] = {
    for {
      hashes  <- getHashesAfter(locator)
      headers <- hashes.mapE(getBlockHeader)
    } yield headers
  }

  def getHeight(bh: BlockHeader): IOResult[Int] = getHeight(bh.hash)

  def getWeight(bh: BlockHeader): IOResult[Weight] = getWeight(bh.hash)

  def isTip(bh: BlockHeader): Boolean = isTip(bh.hash)
}
