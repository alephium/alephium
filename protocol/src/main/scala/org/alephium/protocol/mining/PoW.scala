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

package org.alephium.protocol.mining

import akka.util.ByteString

import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{BlockHash, BlockHeader, ChainIndex, FlowData, Target}
import org.alephium.serde._

object PoW {
  def hash(header: BlockHeader): BlockHash = {
    val serialized = serialize(header)
    hash(serialized)
  }

  def hash(headerBlob: ByteString): BlockHash = {
    BlockHash.doubleHash(headerBlob)
  }

  def checkWork(data: FlowData): Boolean = {
    checkWork(data, data.target)
  }

  def checkWork(data: FlowData, target: Target): Boolean = {
    checkWork(data.hash, target)
  }

  def checkWork(hash: BlockHash, target: Target): Boolean = {
    val current = BigInt(1, hash.bytes.toArray)
    current.compareTo(target.value) <= 0
  }

  def checkMined(data: FlowData, index: ChainIndex): Boolean = {
    data.chainIndex == index && checkWork(data)
  }

  def checkMined(index: ChainIndex, headerBlob: ByteString, target: Target)(implicit
      groupConfig: GroupConfig
  ): Boolean = {
    val blockHash = hash(headerBlob)
    ChainIndex.from(blockHash) == index && checkWork(blockHash, target)
  }
}
