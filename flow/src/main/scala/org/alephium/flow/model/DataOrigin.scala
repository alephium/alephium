package org.alephium.flow.model

import org.alephium.protocol.model.PeerId

sealed trait DataOrigin

object DataOrigin {
  case object Local                 extends DataOrigin
  case class Remote(peerId: PeerId) extends DataOrigin
}
