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
import io.circe.{Decoder, Encoder}
import sttp.tapir._
import sttp.tapir.EndpointIO.Example
import sttp.tapir.json.circe.{jsonBody => tapirJsonBody}

import org.alephium.api.CirceUtils.avectorCodec
import org.alephium.api.TapirCodecs
import org.alephium.api.TapirSchemas._
import org.alephium.api.model._
import org.alephium.protocol.{Hash, PublicKey}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.util.{AVector, TimeStamp, U256}

trait Endpoints extends ApiModelCodec with EndpointsExamples with TapirCodecs with StrictLogging {

  implicit def groupConfig: GroupConfig

  type BaseEndpoint[A, B] = Endpoint[A, ApiModel.Error, B, Nothing]

  private val timeIntervalQuery: EndpointInput[TimeInterval] =
    query[TimeStamp]("fromTs")
      .and(query[TimeStamp]("toTs"))
      .validate(
        Validator.custom({ case (from, to) => from <= to }, "`fromTs` must be before `toTs`"))
      .map({ case (from, to) => TimeInterval(from, to) })(timeInterval =>
        (timeInterval.from, timeInterval.to))

  private def jsonBody[T: Encoder: Decoder: Schema: Validator](
      implicit examples: List[Example[T]]) =
    tapirJsonBody[T].examples(examples)

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
      .in("transactions")
      .tag("Transactions")

  private val minersEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .in("miners")
      .tag("Miners")

  private val contractsEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .in("contracts")
      .tag("Contracts")

  private val blockflowEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .in("blockflow")
      .tag("Blockflow")

  val getSelfClique: BaseEndpoint[Unit, SelfClique] =
    infosEndpoint.get
      .in("self-clique")
      .out(jsonBody[SelfClique])
      .summary("Get info about your own clique")

  val getSelfCliqueSynced: BaseEndpoint[Unit, Boolean] =
    infosEndpoint.get
      .in("self-clique-synced")
      .out(jsonBody[Boolean])
      .summary("Is your clique synced?")

  val getInterCliquePeerInfo: BaseEndpoint[Unit, AVector[InterCliquePeerInfo]] =
    infosEndpoint.get
      .in("inter-clique-peer-info")
      .out(jsonBody[AVector[InterCliquePeerInfo]])
      .summary("Get infos about the inter cliques")

  val getBlockflow: BaseEndpoint[TimeInterval, FetchResponse] =
    blockflowEndpoint.get
      .in(timeIntervalQuery)
      .out(jsonBody[FetchResponse])
      .summary("List blocks on the given time interval")

  val getBlock: BaseEndpoint[Hash, BlockEntry] =
    blockflowEndpoint.get
      .in("blocks")
      .in(path[Hash]("block_hash"))
      .out(jsonBody[BlockEntry])
      .summary("Get a block with hash")

  val getBalance: BaseEndpoint[Address, Balance] =
    addressesEndpoint.get
      .in(path[Address]("address"))
      .in("balance")
      .out(jsonBody[Balance])
      .summary("Get the balance of a address")

  val getGroup: BaseEndpoint[Address, Group] =
    addressesEndpoint.get
      .in(path[Address]("address"))
      .in("group")
      .out(jsonBody[Group])
      .summary("Get the group of a address")

  //have to be lazy to let `groupConfig` being initialized
  lazy val getHashesAtHeight: BaseEndpoint[(GroupIndex, GroupIndex, Int), HashesAtHeight] =
    blockflowEndpoint.get
      .in("hashes")
      .in(query[GroupIndex]("fromGroup"))
      .in(query[GroupIndex]("toGroup"))
      .in(query[Int]("height"))
      .out(jsonBody[HashesAtHeight])
      .summary("Get all block's hashes at given height for given groups")

  //have to be lazy to let `groupConfig` being initialized
  lazy val getChainInfo: BaseEndpoint[(GroupIndex, GroupIndex), ChainInfo] =
    blockflowEndpoint.get
      .in("chains")
      .in(query[GroupIndex]("fromGroup"))
      .in(query[GroupIndex]("toGroup"))
      .out(jsonBody[ChainInfo])
      .summary("Get infos about the chain from the given groups")

  //have to be lazy to let `groupConfig` being initialized
  lazy val listUnconfirmedTransactions: BaseEndpoint[(GroupIndex, GroupIndex), AVector[Tx]] =
    transactionsEndpoint.get
      .in("unconfirmed")
      .in(query[GroupIndex]("fromGroup"))
      .in(query[GroupIndex]("toGroup"))
      .out(jsonBody[AVector[Tx]])
      .summary("List unconfirmed transactions")

  val buildTransaction: BaseEndpoint[(PublicKey, Address, U256), BuildTransactionResult] =
    transactionsEndpoint.get
      .in("build")
      .in(query[PublicKey]("fromKey"))
      .in(query[Address]("toAddress"))
      .in(query[U256]("value"))
      .out(jsonBody[BuildTransactionResult])
      .summary("Build an unsigned transaction")

  val sendTransaction: BaseEndpoint[SendTransaction, TxResult] =
    transactionsEndpoint.post
      .in("send")
      .in(jsonBody[SendTransaction])
      .out(jsonBody[TxResult])
      .summary("Send a signed transaction")

  val minerAction: BaseEndpoint[MinerAction, Boolean] =
    minersEndpoint.post
      .in(query[MinerAction]("action"))
      .out(jsonBody[Boolean])
      .summary("Execute an action on miners")

  val compile: BaseEndpoint[Compile, CompileResult] =
    contractsEndpoint.post
      .in("compile")
      .in(jsonBody[Compile])
      .out(jsonBody[CompileResult])
      .summary("Compile a smart contract")

  val buildContract: BaseEndpoint[BuildContract, BuildContractResult] =
    contractsEndpoint.post
      .in("build")
      .in(jsonBody[BuildContract])
      .out(jsonBody[BuildContractResult])
      .summary("Build an unsigned contract")

  val sendContract: BaseEndpoint[SendContract, TxResult] =
    contractsEndpoint.post
      .in("send")
      .in(jsonBody[SendContract])
      .out(jsonBody[TxResult])
      .summary("Send a signed smart contract")
}
