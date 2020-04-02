package org.alephium.flow.io

import org.alephium.protocol.ALF.Hash
import org.alephium.util.AVector

trait HashTreeTipsDB {
  def updateTips(tips: AVector[Hash]): IOResult[Unit]
}
