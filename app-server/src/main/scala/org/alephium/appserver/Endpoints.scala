package org.alephium.appserver

import sttp.tapir._
import sttp.tapir.json.circe.jsonBody

import org.alephium.appserver.ApiModel._
import org.alephium.appserver.TapirCodecs._
import org.alephium.appserver.TapirSchemas._
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util.TimeStamp

trait Endpoints {

  implicit def rpcConfig: RPCConfig
  implicit def groupConfig: GroupConfig

  private val timeIntervalQuery: EndpointInput[TimeInterval] =
    query[TimeStamp]("fromTs")
      .and(query[TimeStamp]("toTs"))
      .validate(
        Validator.custom({ case (from, to) => from <= to }, "`fromTs` must be before `toTs`"))
      .map({ case (from, to) => TimeInterval(from, to) })(timeInterval =>
        (timeInterval.from, timeInterval.to))

  val getBlockflow: Endpoint[TimeInterval, Response.Failure, FetchResponse, Nothing] =
    endpoint.get
      .in("blockflow")
      .in(timeIntervalQuery)
      .out(jsonBody[FetchResponse])
      .errorOut(jsonBody[Response.Failure])

  val getBlock: Endpoint[Hash, Response.Failure, BlockEntry, Nothing] =
    endpoint.get
      .in("blocks")
      .in(path[Hash]("block_hash"))
      .out(jsonBody[BlockEntry])
      .errorOut(jsonBody[Response.Failure])
      .description("Get a block with hash")

  val getBalance: Endpoint[Address, Response.Failure, Balance, Nothing] =
    endpoint.get
      .in("addresses")
      .in(path[Address]("address"))
      .in("balance")
      .out(jsonBody[Balance])
      .errorOut(jsonBody[Response.Failure])
      .description("Get the balance of a address")

  val getGroup: Endpoint[Address, Response.Failure, Group, Nothing] =
    endpoint.get
      .in("addresses")
      .in(path[Address]("address"))
      .in("group")
      .out(jsonBody[Group])
      .errorOut(jsonBody[Response.Failure])
      .description("Get the group of a address")

  //have to be lazy to let `groupConfig` being initialized
  lazy val getHashesAtHeight
    : Endpoint[(GroupIndex, GroupIndex, Int), Response.Failure, HashesAtHeight, Nothing] =
    endpoint.get
      .in("hashes")
      .in(query[GroupIndex]("fromGroup"))
      .in(query[GroupIndex]("toGroup"))
      .in(query[Int]("height"))
      .out(jsonBody[HashesAtHeight])
      .errorOut(jsonBody[Response.Failure])

  //have to be lazy to let `groupConfig` being initialized
  lazy val getChainInfo: Endpoint[(GroupIndex, GroupIndex), Response.Failure, ChainInfo, Nothing] =
    endpoint.get
      .in("chains")
      .in(query[GroupIndex]("fromGroup"))
      .in(query[GroupIndex]("toGroup"))
      .out(jsonBody[ChainInfo])
      .errorOut(jsonBody[Response.Failure])

  val getOpenapi =
    endpoint.get
      .in("openapi.yaml")
      .out(plainBody[String])
}
