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
    build("17dReod9M5iLsf87ebJGLa4ybRZcFog1ewV6y2zUNHWu5", ALF.alf(Number.million)),
    build("14FGvG61tqzXXYi6UKtzjozMjxCArF1beoU4ogUqM2pSG", ALF.alf(Number.million)),
    build("15qNxou4d5AnPkTgS93xezWpSyZgqegNjjf41QoMqi5Bf", ALF.alf(Number.million)),
    build("1BDwKf9SPzrzQ6wBeWfUNB9yi615MEM9zJeHfkvPnmVnW", ALF.alf(Number.million)),
    build("1EEFFBGYac9ZbXKscqTdfbCd4siW1Yn8YYTo9CPGT811c", ALF.alf(Number.million))
  )

  private val devnet: AVector[(LockupScript.Asset, U256)] = AVector(
    build("151L7hsQgZKJaPeDSCCTMHRyjCrLAvjcwJMrxwbMCy62X", ALF.alf(Number.million)),
    build("12po1BW4WJH81NGcZ3gsn5zP35PSuJq8okMq6ht6qJwyn", ALF.alf(Number.million)),
    build("143jS8xaGNNRes4f1mxJWSpQcqj2xjsXUeu3xQqYgFm5h", ALF.alf(Number.million))
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
