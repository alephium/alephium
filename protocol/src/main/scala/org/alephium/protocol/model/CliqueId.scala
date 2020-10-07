// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

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

object CliqueId
    extends RandomBytes.Companion[CliqueId](bs => {
      assume(bs.size == cliqueIdLength)
      new CliqueId(bs)
    }, _.bytes) {
  override def length: Int = cliqueIdLength

  def fromStringUnsafe(s: String): CliqueId = {
    val bytes = ByteString.fromString(s)
    unsafe(bytes)
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

  def hammingDist(byte0: Byte, byte1: Byte): Int = {
    val xor = byte0 ^ byte1
    countLookUp(xor & 0x0F) + countLookUp((xor >> 4) & 0x0F)
  }
}
