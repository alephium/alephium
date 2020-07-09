package org.alephium.appserver

import sttp.tapir._
import sttp.tapir.json.circe.jsonBody

import org.alephium.appserver.ApiModel._
import org.alephium.appserver.TapirCodecs._
import org.alephium.appserver.TapirSchemas._
import org.alephium.protocol.ALF.Hash
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util.TimeStamp

trait Endpoints {

  implicit def rpcConfig: RPCConfig

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

  val getOpenapi =
    endpoint.get
      .in("openapi.yaml")
      .out(plainBody[String])

}
