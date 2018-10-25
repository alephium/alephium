package org.alephium.flow.model
import java.net.InetSocketAddress

sealed trait DataOrigin

object DataOrigin {
  case object Local                            extends DataOrigin
  case class Remote(remote: InetSocketAddress) extends DataOrigin
}
