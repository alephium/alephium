package org.alephium.flow.model

import org.alephium.protocol.model.CliqueId

sealed trait DataOrigin {
  def isFrom(another: CliqueId): Boolean
}

object DataOrigin {
  case object LocalMining extends DataOrigin {
    override def isFrom(another: CliqueId): Boolean = false
  }
  case class Remote(cliqueId: CliqueId) extends DataOrigin {
    override def isFrom(another: CliqueId): Boolean = cliqueId == another
  }
}
