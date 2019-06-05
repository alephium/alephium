package org.alephium.flow.model

import org.alephium.protocol.model.CliqueId

sealed trait DataOrigin

object DataOrigin {
  case object LocalMining               extends DataOrigin
  case class Remote(cliqueId: CliqueId) extends DataOrigin
}
