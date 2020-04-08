package org.alephium.protocol

import org.alephium.crypto.Keccak256
import org.alephium.serde.{serialize, Serde}
import org.alephium.util.TimeStamp

object ALF {
  //scalastyle:off magic.number
  val MaxALFValue: BigInt   = BigInt(100000000)
  val CoinBaseValue: BigInt = BigInt(10) // Note: temporary value

  val GenesisHeight: Int          = 0
  val GenesisWeight: BigInt       = 0
  val GenesisTimestamp: TimeStamp = TimeStamp.zero
  //scalastyle:on magic.number

  type Hash = Keccak256
  val Hash: Keccak256.type = Keccak256

  abstract class HashSerde[T: Serde] { self: T =>
    def hash: Hash

    protected def _getHash: Hash = Hash.hash(serialize[T](this))

    def shortHex: String = hash.shortHex
  }
}
