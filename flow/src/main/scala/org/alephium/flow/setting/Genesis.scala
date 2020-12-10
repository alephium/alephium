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
    build("T149bUQbTo6tHa35U3QC1tsAkEDaryyQGJD2S8eomYfcZx", ALF.alf(Number.million), Testnet),
    build("T1D9PBcRXK5uzrNYokNMB7oh6JpW86sZajJ5gD845cshED", ALF.alf(Number.million), Testnet),
    build("T1CcSu3nFtFGwaWHnCK4wQkcoJQ2qumDUSH2Xerd35mnUd", ALF.alf(Number.million), Testnet),
    build("T1GGgP5pyNoe8Y9qJ7Wr1nEiJJXk8vAECXKYZKACpMxTZ4", ALF.alf(Number.million), Testnet)
  )

  private val devnet: AVector[(LockupScript, U256)] = AVector(
    build("D19zzHckZmX9Sjs6yERD15JBLa7HhVXfdrUAMRmLgKFpcr", ALF.alf(Number.million), Devnet),
    build("D15kHgMQX6ZMH3prxEFcFDkFBv4B7dcffCwVSRCr8nUe7N", ALF.alf(Number.million), Devnet),
    build("D1GarGSbjFGWEQzJkAPrPoTghHBBDU1FgGSMp2TYdqcx64", ALF.alf(Number.million), Devnet)
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
