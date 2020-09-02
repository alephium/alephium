package org.alephium.wallet

import org.alephium.crypto.wallet.BIP32
import org.alephium.util.AVector
// scalastyle:off magic.number
object Constants {

  val coinType: Int      = 1234
  val path: AVector[Int] = AVector(BIP32.harden(44), BIP32.harden(coinType), BIP32.harden(0), 0, 0)
  val pathStr: String    = s"m/44'/$coinType'/0'/0/0"
}
// scalastyle:on magic.number
