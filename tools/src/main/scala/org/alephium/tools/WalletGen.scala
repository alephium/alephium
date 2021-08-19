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

package org.alephium.tools

import scala.annotation.tailrec

import org.alephium.crypto.{SecP256K1PrivateKey, SecP256K1PublicKey}
import org.alephium.crypto.wallet.{BIP32, Mnemonic}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Address, ChainId, GroupIndex}
import org.alephium.wallet.Constants

@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
object WalletGen extends App {
  @tailrec
  def gen(chainId: ChainId, groupIndex: GroupIndex)(implicit
      config: GroupConfig
  ): (Address, SecP256K1PublicKey, SecP256K1PrivateKey, Mnemonic) = {
    // scalastyle:off magic.number
    val mnemonic = Mnemonic.generate(24).get
    // scalastyle:on magic.number

    val seed        = mnemonic.toSeed(None)
    val extendedKey = BIP32.btcMasterKey(seed).derive(Constants.path(chainId)).get
    val priKey      = extendedKey.privateKey
    val pubKey      = extendedKey.publicKey
    val address     = Address.p2pkh(pubKey)
    if (address.groupIndex == groupIndex) {
      (address, pubKey, priKey, mnemonic)
    } else {
      gen(chainId, groupIndex)
    }
  }

  // scalastyle:off regex
  Seq[(ChainId, Int)](ChainId(1) -> 4, ChainId(2) -> 3).foreach { case (chainId, groupNum) =>
    printLine(chainId.networkType.name)
    implicit val config: GroupConfig = new GroupConfig {
      override def groups: Int = groupNum
    }
    (0 until groupNum).foreach { g =>
      printLine(s"group: $g")
      val (address, pubKey, priKey, mnemonic) = gen(chainId, GroupIndex.unsafe(g))
      printLine(s"address: ${address.toBase58}")
      printLine(s"pubKey: ${pubKey.toHexString}")
      printLine(s"priKey: ${priKey.toHexString}")
      printLine(s"mnemonic: ${mnemonic.toLongString}")
    }
    printLine("")
  }

  def printLine(text: String): Unit = {
    print(text + "\n")
  }
}
