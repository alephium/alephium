package org.alephium.protocol

import org.alephium.util.{TimeStamp, U64}

object ALF {
  //scalastyle:off magic.number
  val MaxALFValue: U64   = U64.unsafe(100000000)
  val CoinBaseValue: U64 = U64.unsafe(10) // Note: temporary value

  val GenesisHeight: Int          = 0
  val GenesisWeight: BigInt       = 0
  val GenesisTimestamp: TimeStamp = TimeStamp.zero

  val MaxTxInputNum: Int     = 1024
  val MaxTxOutputNum: Int    = 1024
  val MaxOutputDataSize: Int = 256
  //scalastyle:on magic.number

}
