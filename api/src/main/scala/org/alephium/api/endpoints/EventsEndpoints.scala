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

trait EventsEndpoints extends BaseEndpoints {

  private val eventsEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .in("events")
      .tag("Events")

  private val contractEventsEndpoint: BaseEndpoint[Unit, Unit] =
    eventsEndpoint
      .in("contract")

  lazy val getEventsByTxId
      : BaseEndpoint[(TransactionId, Option[GroupIndex]), ContractEventsByTxId] =
    eventsEndpoint
      .in("tx-id")
      .get
      .in(path[TransactionId]("txId"))
      .in(query[Option[GroupIndex]]("group"))
      .out(jsonBody[ContractEventsByTxId])
      .summary("Get contract events for a transaction")

  lazy val getEventsByBlockHash
      : BaseEndpoint[(BlockHash, Option[GroupIndex]), ContractEventsByBlockHash] =
    eventsEndpoint
      .in("block-hash")
      .get
      .in(path[BlockHash]("blockHash"))
      .in(query[Option[GroupIndex]]("group"))
      .out(jsonBody[ContractEventsByBlockHash])
      .summary("Get contract events for a block")

  lazy val getContractEvents
      : BaseEndpoint[(Address.Contract, CounterRange, Option[GroupIndex]), ContractEvents] =
    contractEventsEndpoint.get
      .in(path[Address.Contract]("contractAddress"))
      .in(counterQuery)
      .in(query[Option[GroupIndex]]("group"))
      .out(jsonBody[ContractEvents])
      .summary("Get events for a contract within a counter range")

  val getContractEventsCurrentCount: BaseEndpoint[Address.Contract, Int] =
    contractEventsEndpoint.get
      .in(path[Address.Contract]("contractAddress"))
      .in("current-count")
      .out(jsonBody[Int])
      .summary("Get current value of the events counter for a contract")
}
