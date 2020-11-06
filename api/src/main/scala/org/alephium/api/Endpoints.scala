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

import scala.concurrent.Future

import com.typesafe.scalalogging.StrictLogging
import sttp.tapir._
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.PartialServerEndpoint

import org.alephium.api.ApiModel._
import org.alephium.api.CirceUtils.avectorCodec
import org.alephium.api.TapirCodecs
import org.alephium.api.TapirSchemas._
import org.alephium.crypto.Sha256
import org.alephium.protocol.{Hash, PublicKey}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.util.{AVector, TimeStamp, U256}

trait Endpoints extends ApiModelCodec with TapirCodecs with StrictLogging {

  implicit def groupConfig: GroupConfig

  def maybeApiKeyHash: Option[Sha256]

  type BaseEndpoint[A, B] = Endpoint[A, ApiModel.Error, B, Nothing]
  type AuthEndpoint[A, B] = PartialServerEndpoint[ApiKey, A, ApiModel.Error, B, Nothing, Future]

  private val apiKeyHash = maybeApiKeyHash.getOrElse {
    val apiKey = Hash.generate.toHexString
    logger.info(s"Api Key is '$apiKey'")
    Sha256.hash(apiKey)
  }

  private val timeIntervalQuery: EndpointInput[TimeInterval] =
    query[TimeStamp]("fromTs")
      .and(query[TimeStamp]("toTs"))
      .validate(
        Validator.custom({ case (from, to) => from <= to }, "`fromTs` must be before `toTs`"))
      .map({ case (from, to) => TimeInterval(from, to) })(timeInterval =>
        (timeInterval.from, timeInterval.to))

  private def checkApiKey(apiKey: ApiKey): Either[ApiModel.Error, ApiKey] =
    if (apiKey.hash == apiKeyHash) {
      Right(apiKey)
    } else {
      Left(ApiModel.Error.UnauthorizedError)
    }

  private val baseEndpoint: BaseEndpoint[Unit, Unit] =
    endpoint
      .errorOut(jsonBody[ApiModel.Error])
      .tag("Blockflow")

  private val authEndpoint: AuthEndpoint[Unit, Unit] =
    baseEndpoint
      .in(auth.apiKey(header[ApiKey]("X-API-KEY")))
      .serverLogicForCurrent(apiKey => Future.successful(checkApiKey(apiKey)))

  val getSelfClique: BaseEndpoint[Unit, SelfClique] =
    baseEndpoint.get
      .in("infos")
      .in("self-clique")
      .out(jsonBody[SelfClique])

  val getSelfCliqueSynced: BaseEndpoint[Unit, Boolean] =
    baseEndpoint.get
      .in("infos")
      .in("self-clique-synced")
      .out(jsonBody[Boolean])

  val getInterCliquePeerInfo: BaseEndpoint[Unit, AVector[InterCliquePeerInfo]] =
    baseEndpoint.get
      .in("infos")
      .in("inter-clique-peer-info")
      .out(jsonBody[AVector[InterCliquePeerInfo]])

  val getBlockflow: BaseEndpoint[TimeInterval, FetchResponse] =
    baseEndpoint.get
      .in("blockflow")
      .in(timeIntervalQuery)
      .out(jsonBody[FetchResponse])

  val getBlock: BaseEndpoint[Hash, BlockEntry] =
    baseEndpoint.get
      .in("blocks")
      .in(path[Hash]("block_hash"))
      .out(jsonBody[BlockEntry])
      .description("Get a block with hash")

  val getBalance: BaseEndpoint[Address, Balance] =
    baseEndpoint.get
      .in("addresses")
      .in(path[Address]("address"))
      .in("balance")
      .out(jsonBody[Balance])
      .description("Get the balance of a address")

  val getGroup: BaseEndpoint[Address, Group] =
    baseEndpoint.get
      .in("addresses")
      .in(path[Address]("address"))
      .in("group")
      .out(jsonBody[Group])
      .description("Get the group of a address")

  //have to be lazy to let `groupConfig` being initialized
  lazy val getHashesAtHeight: BaseEndpoint[(GroupIndex, GroupIndex, Int), HashesAtHeight] =
    baseEndpoint.get
      .in("hashes")
      .in(query[GroupIndex]("fromGroup"))
      .in(query[GroupIndex]("toGroup"))
      .in(query[Int]("height"))
      .out(jsonBody[HashesAtHeight])

  //have to be lazy to let `groupConfig` being initialized
  lazy val getChainInfo: BaseEndpoint[(GroupIndex, GroupIndex), ChainInfo] =
    baseEndpoint.get
      .in("chains")
      .in(query[GroupIndex]("fromGroup"))
      .in(query[GroupIndex]("toGroup"))
      .out(jsonBody[ChainInfo])

  //have to be lazy to let `groupConfig` being initialized
  lazy val listUnconfirmedTransactions: BaseEndpoint[(GroupIndex, GroupIndex), AVector[Tx]] =
    baseEndpoint.get
      .in("unconfirmed-transactions")
      .in(query[GroupIndex]("fromGroup"))
      .in(query[GroupIndex]("toGroup"))
      .out(jsonBody[AVector[Tx]])
      .description("List unconfirmed transactions")

  val createTransaction: BaseEndpoint[(PublicKey, Address, U256), CreateTransactionResult] =
    baseEndpoint.get
      .in("unsigned-transactions")
      .in(query[PublicKey]("fromKey"))
      .in(query[Address]("toAddress"))
      .in(query[U256]("value"))
      .out(jsonBody[CreateTransactionResult])
      .description("Create an unsigned transaction")

  val sendTransaction: AuthEndpoint[SendTransaction, TxResult] =
    authEndpoint.post
      .in("transactions")
      .in(jsonBody[SendTransaction])
      .out(jsonBody[TxResult])
      .description("Send a signed transaction")

  val minerAction: BaseEndpoint[MinerAction, Boolean] =
    baseEndpoint.post
      .in("miners")
      .in(query[MinerAction]("action"))
      .out(jsonBody[Boolean])
      .description("Execute an action on miners")

  val compile: BaseEndpoint[Compile, CompileResult] =
    baseEndpoint.post
      .in("compile")
      .in(jsonBody[Compile])
      .out(jsonBody[CompileResult])
      .description("Compile a smart contract")

  val createContract: BaseEndpoint[CreateContract, CreateContractResult] =
    baseEndpoint.post
      .in("unsigned-contracts")
      .in(jsonBody[CreateContract])
      .out(jsonBody[CreateContractResult])
      .description("Create an unsigned contracts")

  val sendContract: BaseEndpoint[SendContract, TxResult] =
    baseEndpoint.post
      .in("contracts")
      .in(jsonBody[SendContract])
      .out(jsonBody[TxResult])
      .description("Compile a smart contract")
}
