package org.alephium.protocol.message

import org.alephium.serde.Serde

case class Header(version: Int, cmdCode: Int)

object Header {
  implicit val serde: Serde[Header] =
    Serde.forProduct2(apply, nh => (nh.version, nh.cmdCode))
}
