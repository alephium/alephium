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

package org.alephium.wallet

import org.alephium.crypto.wallet.BIP32
import org.alephium.util.AVector

// scalastyle:off magic.number
object Constants {
  // As defined in:
  // https://github.com/bitcoin/bips/blob/master/bip-0044.mediawiki
  // https://github.com/satoshilabs/slips/blob/master/slip-0044.md
  lazy val path: AVector[Int] = {
    val purpose      = 44
    val coinType     = 1234
    val account      = 0
    val change       = 0
    val addressIndex = 0

    AVector(
      BIP32.harden(purpose),
      BIP32.harden(coinType),
      BIP32.harden(account),
      change,
      addressIndex
    )
  }

  val walletFileVersion: Int = 1
}
// scalastyle:on magic.number
