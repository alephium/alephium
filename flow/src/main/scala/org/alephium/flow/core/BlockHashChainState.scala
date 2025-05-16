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
import org.alephium.flow.setting.ConsensusSettings
import org.alephium.io.IOResult
import org.alephium.protocol.model.BlockHash
import org.alephium.util.{AVector, ConcurrentHashMap, TimeStamp}

trait BlockHashChainState {
  import BlockHashChainState.TipInfo

  var numHashes: Int = 0

  protected val tips = ConcurrentHashMap.empty[BlockHash, TipInfo]

  def consensusConfigs: ConsensusSettings

  protected def chainStateStorage: ChainStateStorage

  def getTimestamp(hash: BlockHash): IOResult[TimeStamp]
  def getTimestampUnsafe(hash: BlockHash): TimeStamp

  def setGenesisState(newTip: BlockHash, blockTs: TimeStamp): IOResult[Unit] = {
    numHashes += 1
    tips.put(newTip, TipInfo(blockTs, TimeStamp.now()))
    pruneDueto(blockTs)
    updateDB()
  }

  def loadStateFromStorage(): IOResult[Unit] = {
    for {
      state <- chainStateStorage.loadState()
      pairs <- state.tips.mapE(tip => getTimestamp(tip).map(tip -> _))
    } yield {
      numHashes = state.numHashes
      val now = TimeStamp.now()
      pairs.foreach { case (tip, blockTs) =>
        tips.put(tip, TipInfo(blockTs, now))
      }
    }
  }

  def updateState(newTip: BlockHash, blockTs: TimeStamp, parent: BlockHash): IOResult[Unit] = {
    numHashes += 1
    tips.put(newTip, TipInfo(blockTs, TimeStamp.now()))
    tips.remove(parent)
    pruneDueto(blockTs)
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
      if (entry.getValue.blockTs + consensusConfigs.tipsPruneDuration < timeStamp) {
        tips.remove(entry.getKey)
      }
    }
  }
}

object BlockHashChainState {
  final case class TipInfo(blockTs: TimeStamp, cacheTs: TimeStamp)
}
