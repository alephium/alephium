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

import org.alephium.flow.io.ChainStateStorage
import org.alephium.flow.setting.ConsensusSetting
import org.alephium.io.IOResult
import org.alephium.protocol.model.BlockHash
import org.alephium.util.{AVector, ConcurrentHashMap, TimeStamp}

trait BlockHashChainState {
  var numHashes: Int = 0

  protected val tips = ConcurrentHashMap.empty[BlockHash, TimeStamp]

  implicit def consensusConfig: ConsensusSetting

  protected def chainStateStorage: ChainStateStorage

  def getTimestamp(hash: BlockHash): IOResult[TimeStamp]
  def getTimestampUnsafe(hash: BlockHash): TimeStamp

  def setGenesisState(newTip: BlockHash, timeStamp: TimeStamp): IOResult[Unit] = {
    numHashes += 1
    tips.put(newTip, timeStamp)
    pruneDueto(timeStamp)
    updateDB()
  }

  def loadStateFromStorage(): IOResult[Unit] = {
    for {
      state <- chainStateStorage.loadState()
      pairs <- state.tips.mapE(tip => getTimestamp(tip).map(tip -> _))
    } yield {
      numHashes = state.numHashes
      pairs.foreach { case (tip, timestamp) =>
        tips.put(tip, timestamp)
      }
    }
  }

  def updateState(newTip: BlockHash, timeStamp: TimeStamp, parent: BlockHash): IOResult[Unit] = {
    numHashes += 1
    tips.put(newTip, timeStamp)
    tips.remove(parent)
    pruneDueto(timeStamp)
    updateDB()
  }

  def removeInvalidTip(tip: BlockHash): Unit = {
    tips.remove(tip)
    ()
  }

  @inline
  private def updateDB(): IOResult[Unit] = {
    val state = BlockHashChain.State(numHashes, AVector.from(tips.keys()))
    chainStateStorage.updateState(state)
  }

  @inline
  private def pruneDueto(timeStamp: TimeStamp): Unit = {
    tips.entries().foreach { entry =>
      if (entry.getValue + consensusConfig.tipsPruneDuration < timeStamp) {
        tips.remove(entry.getKey)
      }
    }
  }
}
