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
import org.alephium.util.{Number, TimeStamp, U256}

object ALF {
  //scalastyle:off magic.number
  val CoinInOneALF: U256     = U256.unsafe(Number.quintillion)
  val CoinInOneCent: U256    = CoinInOneALF divUnsafe U256.unsafe(100)
  val CoinInOneNanoAlf: U256 = U256.unsafe(Number.billion)

  val MaxALFValue: U256 = U256.Billion mulUnsafe CoinInOneALF

  val GenesisHeight: Int          = 0
  val GenesisWeight: Weight       = Weight.zero
  val GenesisTimestamp: TimeStamp = TimeStamp.unsafe(1231006505000L) // BTC genesis timestamp
  val LaunchTimestamp: TimeStamp  = TimeStamp.unsafe(1633438948000L) // 2021-10-05T13:04:28+00:00

  val MaxTxInputNum: Int     = 256
  val MaxTxOutputNum: Int    = 256
  val MaxOutputDataSize: Int = 256
  val MaxScriptSigNum: Int   = 32
  //scalastyle:on magic.number

  def alf(amount: U256): Option[U256] = amount.mul(CoinInOneALF)

  def alf(amount: Long): U256 = {
    assume(amount >= 0)
    U256.unsafe(amount).mulUnsafe(CoinInOneALF)
  }

  def cent(amount: Long): U256 = {
    assume(amount >= 0)
    U256.unsafe(amount).mulUnsafe(CoinInOneCent)
  }

  def nanoAlf(amount: Long): U256 = {
    assume(amount >= 0)
    U256.unsafe(amount).mulUnsafe(CoinInOneNanoAlf)
  }

  val oneAlf: U256     = CoinInOneALF
  val oneNanoAlf: U256 = CoinInOneNanoAlf
}
