package org.alephium.protocol.model

import akka.util.ByteString
import org.alephium.macros.HPC.cfor
import org.alephium.protocol.config.GroupConfig
import org.alephium.serde.RandomBytes

/** 160bits identifier of a Peer **/
class PeerId private (val bytes: ByteString) extends RandomBytes {
  def groupIndex(implicit config: GroupConfig): GroupIndex = {
    GroupIndex(math.abs(bytes.last.toInt) % config.groups)
  }

  def hammingDist(another: PeerId): Int = {
    PeerId.hammingDist(this, another)
  }
}

object PeerId extends RandomBytes.Companion[PeerId](new PeerId(_), _.bytes) {
  override def length: Int = peerIdLength
  def bitLength: Int       = length * 8

  /** Return the distance between two peers as the XOR of their identifier. **/
  def distance(p0: PeerId, p1: PeerId): BigInt = {
    val xs = Array.tabulate[Byte](length) { i =>
      (p0.bytes(i) ^ p1.bytes(i)).toByte
    }

    BigInt(1, xs)
  }

  def ordering(origin: PeerId): Ordering[PeerId] = Ordering.by(distance(origin, _))

  def hammingDist(peerId0: PeerId, peerId1: PeerId): Int = {
    val bytes0 = peerId0.bytes
    val bytes1 = peerId1.bytes
    var dist   = 0
    cfor(0)(_ < length, _ + 1) { i =>
      dist += hammingDist(bytes0(i), bytes1(i))
    }
    dist
  }

  def hammingOrder(target: PeerId): Ordering[PeerId] = Ordering.by(hammingDist(target, _))

  private val countLookUp = Array(0, 1, 1, 2, 1, 2, 2, 3, 1, 2, 2, 3, 2, 3, 3, 4)

  def hammingDist(byte0: Byte, byte1: Byte): Int = {
    val xor = byte0 ^ byte1
    countLookUp(xor & 0x0F) + countLookUp((xor >> 4) & 0x0F)
  }

  def generateFor(mainGroup: GroupIndex)(implicit config: GroupConfig): PeerId = {
    assert(mainGroup.value < config.groups)

    val id = PeerId.generate
    if (id.groupIndex == mainGroup) {
      id
    } else generateFor(mainGroup)
  }
}
