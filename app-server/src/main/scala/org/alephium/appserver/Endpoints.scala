package org.alephium.appserver

import scala.concurrent.Future

import sttp.tapir._
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.PartialServerEndpoint

import org.alephium.appserver.ApiModel._
import org.alephium.appserver.TapirCodecs._
import org.alephium.appserver.TapirSchemas._
import org.alephium.crypto.ED25519PublicKey
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util.{TimeStamp, U64}

trait Endpoints {

  implicit def rpcConfig: RPCConfig
  implicit def groupConfig: GroupConfig

  type BaseEndpoint[A, B] = Endpoint[A, Response.Failure, B, Nothing]
  type AuthEndpoint[A, B] = PartialServerEndpoint[ApiKey, A, Response.Failure, B, Nothing, Future]

  private val timeIntervalQuery: EndpointInput[TimeInterval] =
    query[TimeStamp]("fromTs")
      .and(query[TimeStamp]("toTs"))
      .validate(
        Validator.custom({ case (from, to) => from <= to }, "`fromTs` must be before `toTs`"))
      .map({ case (from, to) => TimeInterval(from, to) })(timeInterval =>
        (timeInterval.from, timeInterval.to))

  private def checkApiKey(apiKey: ApiKey): Either[Response.Failure, ApiKey] =
    if (apiKey.hash == rpcConfig.apiKeyHash) {
      Right(apiKey)
    } else {
      Left(Response.failed(Error.UnauthorizedError))
    }

  private val baseEndpoint: BaseEndpoint[Unit, Unit] =
    endpoint
      .errorOut(jsonBody[Response.Failure])

  private val authEndpoint: AuthEndpoint[Unit, Unit] =
    baseEndpoint
      .in(auth.apiKey(header[ApiKey]("X-API-KEY")))
      .serverLogicForCurrent(apiKey => Future.successful(checkApiKey(apiKey)))

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

  val createTransaction: BaseEndpoint[(ED25519PublicKey, Address, U64), CreateTransactionResult] =
    baseEndpoint.get
      .in("unsigned-transactions")
      .in(query[ED25519PublicKey]("fromKey"))
      .in(query[Address]("toAddress"))
      .in(query[U64]("value"))
      .out(jsonBody[CreateTransactionResult])
      .description("Create an unsigned transaction")

  val sendTransaction: AuthEndpoint[SendTransaction, TxResult] =
    authEndpoint.post
      .in("transactions")
      .in(jsonBody[SendTransaction])
      .out(jsonBody[TxResult])
      .description("Send a signed transaction")

  val minerAction: AuthEndpoint[MinerAction, Boolean] =
    authEndpoint.post
      .in("miners")
      .in(query[MinerAction]("action"))
      .out(jsonBody[Boolean])
      .description("Execute an action on miners")

  val getOpenapi =
    baseEndpoint.get
      .in("openapi.yaml")
      .out(plainBody[String])
}
