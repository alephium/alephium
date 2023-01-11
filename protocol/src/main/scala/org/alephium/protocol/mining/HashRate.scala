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

import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.Target
import org.alephium.util.Duration

// unit: hash per second
final case class HashRate private (value: BigInteger) extends AnyVal with Ordered[HashRate] {
  override def compare(that: HashRate): Int = this.value.compareTo(that.value)

  def multiply(n: Long): HashRate = HashRate.unsafe(value.multiply(BigInteger.valueOf(n)))

  def subtractUnsafe(another: HashRate): HashRate = HashRate.unsafe(value.subtract(another.value))

  // scalastyle:off magic.number
  def MHs: String = s"${value.divide(BigInteger.valueOf(1000000))} MH/s"
  // scalastyle:on magic.number
}

object HashRate {
  def unsafe(value: BigInteger): HashRate = new HashRate(value)

  def Min: HashRate = HashRate.unsafe(BigInteger.ONE)

  // scalastyle:off magic.number
  def from(target: Target, blockTime: Duration)(implicit groupConfig: GroupConfig): HashRate = {
    val hashDone = target.getDifficulty().value
    val rate =
      hashDone
        // multiply the hashrate due to: multiple chains and chain index encoding in block hash
        .multiply(BigInteger.valueOf((1000 * groupConfig.chainNum * groupConfig.chainNum).toLong))
        .divide(BigInteger.valueOf(blockTime.millis))
    if (rate == BigInteger.ZERO) Min else unsafe(rate)
  }

  val onePhPerSecond: HashRate  = unsafe(BigInteger.ONE.shiftLeft(50))
  val oneEhPerSecond: HashRate  = unsafe(BigInteger.ONE.shiftLeft(60))
  val a128EhPerSecond: HashRate = unsafe(BigInteger.ONE.shiftLeft(67))
  // scalastyle:on magic.number
}
