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

package org.alephium.protocol

import org.alephium.protocol.model.Weight
import org.alephium.util.{Duration, Number, TimeStamp, U256}

object ALPH {
  //scalastyle:off magic.number
  val CoinInOneALPH: U256     = U256.unsafe(Number.quintillion)
  val CoinInOneCent: U256     = CoinInOneALPH divUnsafe U256.unsafe(100)
  val CoinInOneNanoAlph: U256 = U256.unsafe(Number.billion)

  val MaxALPHValue: U256 = U256.Billion mulUnsafe CoinInOneALPH

  val GenesisHeight: Int          = 0
  val GenesisWeight: Weight       = Weight.zero
  val GenesisTimestamp: TimeStamp = TimeStamp.unsafe(1231006505000L) // BTC genesis timestamp
  val LaunchTimestamp: TimeStamp  = TimeStamp.unsafe(1635628983000L) // 2021-10-30T21:22:58+00:00

  val OneYear: Duration                         = Duration.ofDaysUnsafe(365)
  val DifficultyBombEnabledTimestamp: TimeStamp = LaunchTimestamp.plusUnsafe(OneYear)
  val ExpDiffPeriod: Duration                   = Duration.ofDaysUnsafe(30)

  val MaxTxInputNum: Int     = 256
  val MaxTxOutputNum: Int    = 256
  val MaxOutputDataSize: Int = 256
  val MaxScriptSigNum: Int   = 32
  //scalastyle:on magic.number

  def alph(amount: U256): Option[U256] = amount.mul(CoinInOneALPH)

  def alph(amount: Long): U256 = {
    assume(amount >= 0)
    U256.unsafe(amount).mulUnsafe(CoinInOneALPH)
  }

  def cent(amount: Long): U256 = {
    assume(amount >= 0)
    U256.unsafe(amount).mulUnsafe(CoinInOneCent)
  }

  def nanoAlph(amount: Long): U256 = {
    assume(amount >= 0)
    U256.unsafe(amount).mulUnsafe(CoinInOneNanoAlph)
  }

  val oneAlph: U256     = CoinInOneALPH
  val oneNanoAlph: U256 = CoinInOneNanoAlph

  // x.x ALPH format
  def alphFromString(string: String): Option[U256] = {
    val regex = """([0-9]*\.?[0-9]+) *ALPH""".r
    string match {
      case regex(v) =>
        val bigDecimal = new java.math.BigDecimal(v)
        val scaling    = bigDecimal.scale()
        // scalastyle:off magic.number
        if (scaling > 18) {
          None
        } else {
          U256.from(bigDecimal.movePointRight(18).toBigInteger)
        }
      // scalastyle:on magic.number
      case _ => None
    }
  }
}
