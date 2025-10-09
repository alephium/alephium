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

import org.alephium.api.Endpoints._
import org.alephium.api.endpoints._
import org.alephium.api.model.{Address => _, _}
import org.alephium.protocol.model.{Balance => _, Transaction => _, _}

trait MinersEndpoints extends BaseEndpoints {
  private val minersEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .in("miners")
      .tag("Miners")

  val minerAction: BaseEndpoint[MinerAction, Boolean] =
    minersEndpoint.post
      .in("cpu-mining")
      .in(query[MinerAction]("action").examples(minerActionExamples))
      .out(jsonBody[Boolean])
      .summary("Execute an action on CPU miner. !!! for test only !!!")

  lazy val mineOneBlock: BaseEndpoint[ChainIndex, Boolean] =
    minersEndpoint.post
      .in("cpu-mining")
      .in("mine-one-block")
      .in(chainIndexQuery)
      .out(jsonBody[Boolean])
      .summary("Mine a block on CPU miner. !!! for test only !!!")

  val minerListAddresses: BaseEndpoint[Unit, MinerAddresses] =
    minersEndpoint.get
      .in("addresses")
      .out(jsonBody[MinerAddresses])
      .summary("List miner's addresses")

  val minerUpdateAddresses: BaseEndpoint[MinerAddresses, Unit] =
    minersEndpoint.put
      .in("addresses")
      .in(jsonBody[MinerAddresses])
      .summary("Update miner's addresses, but better to use user.conf instead")
}
