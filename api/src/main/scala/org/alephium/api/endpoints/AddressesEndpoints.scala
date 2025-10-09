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

package org.alephium.api.endpoints

import sttp.tapir._
import sttp.tapir.generic.auto._

import org.alephium.api.{model => api}
import org.alephium.api.Endpoints._
import org.alephium.api.endpoints._
import org.alephium.api.model.{Address => _, _}
import org.alephium.protocol.model.{Balance => _, Transaction => _, _}

trait AddressesEndpoints extends BaseEndpoints {

  private val addressesEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .in("addresses")
      .tag("Addresses")

  val getBalance: BaseEndpoint[(api.Address, Option[Boolean]), Balance] =
    addressesEndpoint.get
      .in(path[api.Address]("address"))
      .in("balance")
      .in(query[Option[Boolean]]("mempool"))
      .out(jsonBodyWithAlph[Balance])
      .summary("Get the balance of an address")

  // TODO: query based on token id?
  val getUTXOs: BaseEndpoint[(Address, Option[Boolean]), UTXOs] =
    addressesEndpoint.get
      .in(path[Address]("address"))
      .in("utxos")
      .in(query[Option[Boolean]]("error-if-exceed-max-utxos"))
      .out(jsonBody[UTXOs])
      .summary("Get the UTXOs of an address")

  val getGroup: BaseEndpoint[Address, Group] =
    addressesEndpoint.get
      .in(path[Address]("address"))
      .in("group")
      .out(jsonBody[Group])
      .summary("Get the group of an address")

  val getGroupLocal: BaseEndpoint[Address, Group] =
    addressesEndpoint.get
      .in(path[Address]("address"))
      .in("groupLocal")
      .out(jsonBody[Group])
      .summary("Get the group of an address. Checks locally for contract addressses")
}
