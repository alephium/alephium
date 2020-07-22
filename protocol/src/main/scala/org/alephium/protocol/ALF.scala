package org.alephium.protocol

import org.alephium.crypto.Keccak256
import org.alephium.serde._
import org.alephium.util.{TimeStamp, U64}

object ALF {
  //scalastyle:off magic.number
  val MaxALFValue: U64   = U64.unsafe(100000000)
  val CoinBaseValue: U64 = U64.unsafe(10) // Note: temporary value

  val GenesisHeight: Int          = 0
  val GenesisWeight: BigInt       = 0
  val GenesisTimestamp: TimeStamp = TimeStamp.zero

  val MaxTxInputNum: Int  = 1024
  val MaxTxOutputNum: Int = 1024
  //scalastyle:on magic.number

  type Hash = Keccak256
  val Hash: Keccak256.type = Keccak256

  abstract class HashSerde[T: Serde] { self: T =>
    def hash: Hash

    protected def _getHash: Hash = Hash.hash(serialize[T](this))

    def shortHex: String = hash.shortHex
  }
}
