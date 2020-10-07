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

  val coinType: Int      = 1234
  val path: AVector[Int] = AVector(BIP32.harden(44), BIP32.harden(coinType), BIP32.harden(0), 0, 0)
  val pathStr: String    = s"m/44'/$coinType'/0'/0/0"

  val walletFileVersion: Int = 1
}
// scalastyle:on magic.number
