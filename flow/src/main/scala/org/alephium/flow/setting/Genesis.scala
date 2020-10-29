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

package org.alephium.flow.setting

import org.alephium.protocol.ALF
import org.alephium.protocol.model.{Address, NetworkType}
import org.alephium.protocol.model.NetworkType.{Devnet, Mainnet, Testnet}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{AVector, Number, U256}

@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
object Genesis {
  def apply(networkType: NetworkType): AVector[(LockupScript, U256)] =
    networkType match {
      case Mainnet => mainnet
      case Devnet  => devnet
      case Testnet => testnet
    }

  // scalastyle:off magic.number
  private val mainnet: AVector[(LockupScript, U256)] = AVector(
    build("M137WR16pkhVpne5xgAFfHvGZbm5sVNW7WvwuXGwxTGAQL", ALF.alf(Number.million), Mainnet),
    build("M1ApQKaajAtTVnxaZ5WeR3V6MfgzXdwc7UksqEjegb2jVB", ALF.alf(Number.million), Mainnet),
    build("M1CZvXSKS2xDRg2JajzVhwu5WcKiBwrNNAXKrWxDWAsexV", ALF.alf(Number.million), Mainnet),
    build("M1BnFUQjiVJCVDDnJY8oX4YLYjAMePeTXTgpVzqnFCUZ5r", ALF.alf(Number.million), Mainnet)
  )

  private val testnet: AVector[(LockupScript, U256)] = AVector(
    build("T1BLeXRTZfktdNrtBC95uVMXSRn7x3Zt9bGNbXywxNTXJ9", ALF.alf(Number.million), Testnet),
    build("T1CarrVGRS2YvEoPLgY6HJvyCAcqW91buYWRJ4roRDCPPg", ALF.alf(Number.million), Testnet),
    build("T18vgWStW8yz2vpeb9gGU5VBcnX72GYbRMPjx8rxXK7R2i", ALF.alf(Number.million), Testnet),
    build("T1Ad1kFbKN64Jq6GeQuLVCwZwQHK7gK9ifPNJqP6mWmQTD", ALF.alf(Number.million), Testnet)
  )

  private val devnet: AVector[(LockupScript, U256)] = AVector(
    build("D1Cpcv6VKJMCxfqEf9fWDxmeSAkairbxYSWziN2YdKmfm8", ALF.alf(Number.million), Devnet),
    build("D197jT2NZiXQz4ru7NfmPfeWRuKKdPGpeBVfcc7vZA2QdF", ALF.alf(Number.million), Devnet),
    build("D1EfN4cKQar8JE2gChzgUgapMqVEHbTnKKeqfwB95XynsF", ALF.alf(Number.million), Devnet)
  )
  // scalastyle:on magic.number

  private def build(addressRaw: String,
                    amount: U256,
                    networkType: NetworkType): (LockupScript, U256) = {
    val address = Address
      .fromBase58(addressRaw, networkType)
      .getOrElse(throw new RuntimeException(s"Invalid address $addressRaw for $networkType"))
    (address.lockupScript, amount)
  }
}
