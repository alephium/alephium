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

package org.alephium.api

import com.typesafe.scalalogging.StrictLogging
import sttp.tapir._
import sttp.tapir.json.circe.jsonBody

import org.alephium.api.CirceUtils.avectorCodec
import org.alephium.api.model._
import org.alephium.api.TapirCodecs
import org.alephium.api.TapirSchemas._
import org.alephium.protocol.{Hash, PublicKey}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.util.{AVector, TimeStamp, U256}

trait Endpoints extends ApiModelCodec with TapirCodecs with StrictLogging {

  implicit def groupConfig: GroupConfig

  type BaseEndpoint[A, B] = Endpoint[A, ApiModel.Error, B, Nothing]

  private val timeIntervalQuery: EndpointInput[TimeInterval] =
    query[TimeStamp]("fromTs")
      .and(query[TimeStamp]("toTs"))
      .validate(
        Validator.custom({ case (from, to) => from <= to }, "`fromTs` must be before `toTs`"))
      .map({ case (from, to) => TimeInterval(from, to) })(timeInterval =>
        (timeInterval.from, timeInterval.to))

  private val baseEndpoint: BaseEndpoint[Unit, Unit] =
    endpoint
      .errorOut(jsonBody[ApiModel.Error])

  private val infosEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .in("infos")
      .tag("Infos")

  private val addressesEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .in("addresses")
      .tag("Addresses")

  private val transactionsEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .tag("Transactions")

  private val minersEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .in("miners")
      .tag("Miners")

  private val contractsEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .tag("Contracts")

  private val blockflowEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .tag("Blockflow")

  val getSelfClique: BaseEndpoint[Unit, SelfClique] =
    infosEndpoint.get
      .in("self-clique")
      .out(jsonBody[SelfClique])

  val getSelfCliqueSynced: BaseEndpoint[Unit, Boolean] =
    infosEndpoint.get
      .in("self-clique-synced")
      .out(jsonBody[Boolean])

  val getInterCliquePeerInfo: BaseEndpoint[Unit, AVector[InterCliquePeerInfo]] =
    infosEndpoint.get
      .in("inter-clique-peer-info")
      .out(jsonBody[AVector[InterCliquePeerInfo]])

  val getBlockflow: BaseEndpoint[TimeInterval, FetchResponse] =
    blockflowEndpoint.get
      .in("blockflow")
      .in(timeIntervalQuery)
      .out(jsonBody[FetchResponse])

  val getBlock: BaseEndpoint[Hash, BlockEntry] =
    blockflowEndpoint.get
      .in("blocks")
      .in(path[Hash]("block_hash"))
      .out(jsonBody[BlockEntry])
      .description("Get a block with hash")

  val getBalance: BaseEndpoint[Address, Balance] =
    addressesEndpoint.get
      .in(path[Address]("address"))
      .in("balance")
      .out(jsonBody[Balance])
      .description("Get the balance of a address")

  val getGroup: BaseEndpoint[Address, Group] =
    addressesEndpoint.get
      .in(path[Address]("address"))
      .in("group")
      .out(jsonBody[Group])
      .description("Get the group of a address")

  //have to be lazy to let `groupConfig` being initialized
  lazy val getHashesAtHeight: BaseEndpoint[(GroupIndex, GroupIndex, Int), HashesAtHeight] =
    blockflowEndpoint.get
      .in("hashes")
      .in(query[GroupIndex]("fromGroup"))
      .in(query[GroupIndex]("toGroup"))
      .in(query[Int]("height"))
      .out(jsonBody[HashesAtHeight])

  //have to be lazy to let `groupConfig` being initialized
  lazy val getChainInfo: BaseEndpoint[(GroupIndex, GroupIndex), ChainInfo] =
    blockflowEndpoint.get
      .in("chains")
      .in(query[GroupIndex]("fromGroup"))
      .in(query[GroupIndex]("toGroup"))
      .out(jsonBody[ChainInfo])

  //have to be lazy to let `groupConfig` being initialized
  lazy val listUnconfirmedTransactions: BaseEndpoint[(GroupIndex, GroupIndex), AVector[Tx]] =
    transactionsEndpoint.get
      .in("unconfirmed-transactions")
      .in(query[GroupIndex]("fromGroup"))
      .in(query[GroupIndex]("toGroup"))
      .out(jsonBody[AVector[Tx]])
      .description("List unconfirmed transactions")

  val createTransaction: BaseEndpoint[(PublicKey, Address, U256), CreateTransactionResult] =
    transactionsEndpoint.get
      .in("unsigned-transactions")
      .in(query[PublicKey]("fromKey"))
      .in(query[Address]("toAddress"))
      .in(query[U256]("value"))
      .out(jsonBody[CreateTransactionResult])
      .description("Create an unsigned transaction")

  val sendTransaction: BaseEndpoint[SendTransaction, TxResult] =
    transactionsEndpoint.post
      .in("transactions")
      .in(jsonBody[SendTransaction])
      .out(jsonBody[TxResult])
      .description("Send a signed transaction")

  val minerAction: BaseEndpoint[MinerAction, Boolean] =
    minersEndpoint.post
      .in(query[MinerAction]("action"))
      .out(jsonBody[Boolean])
      .description("Execute an action on miners")

  val compile: BaseEndpoint[Compile, CompileResult] =
    contractsEndpoint.post
      .in("compile")
      .in(jsonBody[Compile])
      .out(jsonBody[CompileResult])
      .description("Compile a smart contract")

  val createContract: BaseEndpoint[CreateContract, CreateContractResult] =
    contractsEndpoint.post
      .in("unsigned-contracts")
      .in(jsonBody[CreateContract])
      .out(jsonBody[CreateContractResult])
      .description("Create an unsigned contracts")

  val sendContract: BaseEndpoint[SendContract, TxResult] =
    contractsEndpoint.post
      .in("contracts")
      .in(jsonBody[SendContract])
      .out(jsonBody[TxResult])
      .description("Compile a smart contract")
}
