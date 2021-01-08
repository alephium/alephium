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

package org.alephium.api.model

import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.Address
import org.alephium.util.AVector

final case class MinerAddresses(addresses: AVector[Address])

object MinerAddresses {
  def validate(addresses: AVector[Address])(
      implicit groupConfig: GroupConfig): Either[String, MinerAddresses] = {
    if (addresses.length != groupConfig.groups) {
      Left(s"Wrong number of addresses, expected ${groupConfig.groups}, got ${addresses.length}")
    } else {
      addresses
        .foreachWithIndexE { (address, i) =>
          Either.cond(
            address.lockupScript.groupIndex.value == i,
            (),
            s"Address ${address.toBase58} doesn't belong to group $i"
          )
        }
        .map(_ => new MinerAddresses(addresses))
    }
  }
}
