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

import org.alephium.util.{TimeStamp, U256}

object ALF {
  //scalastyle:off magic.number
  val CoinInOneALF: U256  = U256.unsafe(1000000000)
  val MaxALFValue: U256   = U256.unsafe(100) mulUnsafe U256.Million mulUnsafe CoinInOneALF
  val CoinBaseValue: U256 = U256.unsafe(15) // Note: temporary value

  val GenesisHeight: Int          = 0
  val GenesisWeight: BigInt       = 0
  val GenesisTimestamp: TimeStamp = TimeStamp.zero

  val MaxTxInputNum: Int     = 1024
  val MaxTxOutputNum: Int    = 1024
  val MaxOutputDataSize: Int = 256
  //scalastyle:on magic.number

  def alf(amount: U256): Option[U256] = amount.mul(CoinInOneALF)

  def nanoAlf(amount: U256): U256 = amount
}
