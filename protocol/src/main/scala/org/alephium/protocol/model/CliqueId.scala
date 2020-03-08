package org.alephium.protocol.model

import akka.util.ByteString

import org.alephium.macros.HPC.cfor
import org.alephium.serde.RandomBytes

/** 160bits identifier of a Peer **/
class CliqueId private (val bytes: ByteString) extends RandomBytes {
  def hammingDist(another: CliqueId): Int = {
    CliqueId.hammingDist(this, another)
  }
}

object CliqueId extends RandomBytes.Companion[CliqueId](new CliqueId(_), _.bytes) {
  override def length: Int = cliqueIdLength

  def fromBytesUnsafe(bytes: ByteString): CliqueId = {
    assert(bytes.length == length)
    new CliqueId(bytes)
  }

  def fromStringUnsafe(s: String): CliqueId = {
    val bytes = ByteString.fromString(s)
    fromBytesUnsafe(bytes)
  }

  @SuppressWarnings(Array("org.wartremover.warts.While"))
  def hammingDist(cliqueId0: CliqueId, cliqueId1: CliqueId): Int = {
    val bytes0 = cliqueId0.bytes
    val bytes1 = cliqueId1.bytes
    var dist   = 0
    cfor(0)(_ < length, _ + 1) { i =>
      dist += hammingDist(bytes0(i), bytes1(i))
    }
    dist
  }

  def hammingOrder(target: CliqueId): Ordering[CliqueId] = Ordering.by(hammingDist(target, _))

  private val countLookUp = Array(0, 1, 1, 2, 1, 2, 2, 3, 1, 2, 2, 3, 2, 3, 3, 4)

  1 +: Array(1, 2)

  def hammingDist(byte0: Byte, byte1: Byte): Int = {
    val xor = byte0 ^ byte1
    countLookUp(xor & 0x0F) + countLookUp((xor >> 4) & 0x0F)
  }
}
