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

package org.alephium.protocol.mining

import java.math.BigInteger

import org.alephium.protocol.model.Target
import org.alephium.util.Duration

// unit: hash per second
final case class HashRate private (value: BigInteger) extends AnyVal with Ordered[HashRate] {
  override def compare(that: HashRate): Int = this.value.compareTo(that.value)
}

object HashRate {
  def unsafe(value: BigInteger): HashRate = new HashRate(value)

  // scalastyle:off magic.number
  def from(target: Target, blockTime: Duration): HashRate = {
    val hashDone = Target.maxBigInt.divide(target.value)
    unsafe(hashDone.multiply(BigInteger.valueOf(1000)).divide(BigInteger.valueOf(blockTime.millis)))
  }

  val onePhPerSecond: HashRate  = unsafe(BigInteger.ONE.shiftLeft(50))
  val oneEhPerSecond: HashRate  = unsafe(BigInteger.ONE.shiftLeft(60))
  val a128EhPerSecond: HashRate = unsafe(BigInteger.ONE.shiftLeft(67))
  // scalastyle:on magic.number
}
