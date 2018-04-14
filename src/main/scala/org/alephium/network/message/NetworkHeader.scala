package org.alephium.network.message

import org.alephium.serde.Serde

case class NetworkHeader(version: Int, cmdCode: Int)

object NetworkHeader {
  implicit val serde: Serde[NetworkHeader] = Serde.forProduct2(apply, nh => (nh.version, nh.cmdCode))
}
