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
import org.alephium.protocol.model.{Address, NetworkId}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{AVector, Number, U256}

@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
object Genesis {
  def apply(networkId: NetworkId): AVector[(LockupScript.Asset, U256)] =
    networkId.id match {
      case 0 => mainnet
      case 1 => testnet
      case 2 => devnet
      case _ => AVector.empty
    }

  // scalastyle:off magic.number
  private val mainnet: AVector[(LockupScript.Asset, U256)] = AVector(
    build("15uBaW8WhKURyB4auu5HokdNd2CSUY6r1UHJcWe1u4dDN", ALF.alf(Number.million)),
    build("1BLj6uPYbL9hmFza7gwgVyAhNmejFwEgEWYvNnrFo5LdM", ALF.alf(Number.million)),
    build("14uZkwvBUhdCKgExc1Uj2TuH61d4kZPm2sQv98muamYq5", ALF.alf(Number.million)),
    build("1HsLHhtw4PNicRVqWKHVdro7fvkbeDHtQyRjbVpSnNMmH", ALF.alf(Number.million))
  )

  private val testnet: AVector[(LockupScript.Asset, U256)] = AVector(
    build("127TathFRczW5LXeNK2n2A6Qi2EpkamcmvwCrr3y18uHT", ALF.alf(Number.million)),
    build("1HMSFdhPpvPybfWLZiHeBxVbnfTc2L6gkVPHfuJWoZrMA", ALF.alf(Number.million)),
    build("13mUTuGxGQdeiHrzcmgr4VfMwb6chkxzTLpcAV6C4n19T", ALF.alf(Number.million)),
    build("15D4fm3yjXv3AYNrywQyTykUJfsFNJQdauY9Hzonofa9o", ALF.alf(Number.million))
  )

  private val devnet: AVector[(LockupScript.Asset, U256)] = AVector(
    build("17Ey9PHogBdGS5361s6WQeTXNG2KNFoEtdkJVJvM7KKwU", ALF.alf(Number.million)),
    build("1GtpWTB3QEfHZg6Rfr5TCaxRRkLTXhDT1Dtk4Rm8kCPoc", ALF.alf(Number.million)),
    build("17Tyyh8iG5JQkF7DFPeUXzPbK6JG4FW4erCmXHNA6a9qP", ALF.alf(Number.million))
  )
  // scalastyle:on magic.number

  private def build(
      addressRaw: String,
      amount: U256
  ): (LockupScript.Asset, U256) = {
    val address = Address
      .asset(addressRaw)
      .getOrElse(throw new RuntimeException(s"Invalid address $addressRaw"))
    (address.lockupScript, amount)
  }
}
