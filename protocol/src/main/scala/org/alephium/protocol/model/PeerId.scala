package org.alephium.protocol.model

import akka.util.ByteString
import org.alephium.serde.RandomBytes

/** 160bits identifier of a Peer **/
class PeerId private[PeerId] (val bytes: ByteString) extends RandomBytes

object PeerId extends RandomBytes.Companion[PeerId](new PeerId(_), _.bytes) {
  override def length: Int = peerIdLength

  /** Return the distance between two peers as the XOR of their identifier. **/
  def distance(p0: PeerId, p1: PeerId): BigInt = {
    val xs = Array.tabulate[Byte](length) { i =>
      (p0.bytes(i) ^ p1.bytes(i)).toByte
    }

    BigInt(1, xs)
  }

  def ordering(origin: PeerId): Ordering[PeerId] = Ordering.by(distance(origin, _))
}
