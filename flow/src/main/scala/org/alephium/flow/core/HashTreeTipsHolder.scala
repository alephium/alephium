package org.alephium.flow.core

import org.alephium.flow.io.{HashTreeTipsDB, IOResult}
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.config.ConsensusConfig
import org.alephium.util.{AVector, ConcurrentHashMap, TimeStamp}

trait HashTreeTipsHolder {
  protected val tips = ConcurrentHashMap.empty[Hash, TimeStamp]

  protected def config: ConsensusConfig

  protected def tipsDB: HashTreeTipsDB

  def addGenesisTip(newTip: Hash, timeStamp: TimeStamp): IOResult[Unit] = {
    tips.add(newTip, timeStamp)
    pruneDueto(timeStamp)
    updateDB()
  }

  def addNewTip(newTip: Hash, timeStamp: TimeStamp, parent: Hash): IOResult[Unit] = {
    tips.add(newTip, timeStamp)
    tips.remove(parent)
    pruneDueto(timeStamp)
    updateDB()
  }

  @inline
  private def updateDB(): IOResult[Unit] = {
    tipsDB.updateTips(AVector.from(tips.keys))
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
