package org.alephium.flow.core

import org.alephium.flow.io.{ChainStateStorage, IOResult}
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.config.ConsensusConfig
import org.alephium.util.{AVector, ConcurrentHashMap, TimeStamp}

trait BlockHashChainState {
  var numHashes: Int = 0

  protected val tips = ConcurrentHashMap.empty[Hash, TimeStamp]

  protected def config: ConsensusConfig

  protected def chainStateStorage: ChainStateStorage

  def getTimestamp(hash: Hash): IOResult[TimeStamp]

  def setGenesisState(newTip: Hash, timeStamp: TimeStamp): IOResult[Unit] = {
    numHashes += 1
    tips.add(newTip, timeStamp)
    pruneDueto(timeStamp)
    updateDB()
  }

  def loadStateFromStorage(): IOResult[Unit] = {
    for {
      state <- chainStateStorage.loadState()
      pairs <- state.tips.mapE(tip => getTimestamp(tip).map(tip -> _))
    } yield {
      numHashes = state.numHashes
      pairs.foreach {
        case (tip, timestamp) =>
          tips.add(tip, timestamp)
      }
    }
  }

  def updateState(newTip: Hash, timeStamp: TimeStamp, parent: Hash): IOResult[Unit] = {
    numHashes += 1
    tips.add(newTip, timeStamp)
    tips.remove(parent)
    pruneDueto(timeStamp)
    updateDB()
  }

  @inline
  private def updateDB(): IOResult[Unit] = {
    val state = BlockHashChain.State(numHashes, AVector.from(tips.keys))
    chainStateStorage.updateState(state)
  }

  @inline
  private def pruneDueto(timeStamp: TimeStamp): Unit = {
    tips.entries.foreach { entry =>
      if (entry.getValue + config.tipsPruneDuration < timeStamp) {
        tips.remove(entry.getKey)
      }
    }
  }
}
