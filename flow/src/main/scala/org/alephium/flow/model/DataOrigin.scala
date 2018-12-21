package org.alephium.flow.model

import org.alephium.protocol.model.PeerId

sealed trait DataOrigin {
  def isNot(peerId: PeerId): Boolean
}

object DataOrigin {
  case object Local extends DataOrigin {
    override def isNot(peerId: PeerId): Boolean = true
  }
  case class Remote(peerId: PeerId) extends DataOrigin {
    override def isNot(that: PeerId): Boolean = this.peerId != that
  }
}
