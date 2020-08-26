package org.alephium.util

import scala.annotation.tailrec

object Bits {
  def from(byte: Byte): AVector[Boolean] = {
    AVector.tabulate(8) { k =>
      val bit = (byte >> (7 - k)) & 1
      bit == 1
    }
  }

  def toInt(bits: AVector[Boolean]): Int = {
    @tailrec
    def loop(i: Int, acc: Int): Int = {
      if (i == bits.length) acc
      else {
        val newAcc = (acc << 1) + (if (bits(i)) 1 else 0)
        loop(i + 1, newAcc)
      }
    }
    loop(0, 0)
  }
}
