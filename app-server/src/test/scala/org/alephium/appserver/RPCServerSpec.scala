package org.alephium.appserver

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.server.{MethodRejection, UnsupportedRequestContentTypeRejection}
import akka.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestProbe
import akka.util.Timeout
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.{Decoder, Json}
import io.circe.parser._
import io.circe.syntax._
import org.scalatest.{Assertion, EitherValues}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Span}

import org.alephium.flow.client.Miner
import org.alephium.flow.handler.FlowHandler.BlockNotify
import org.alephium.flow.platform.Mode
import org.alephium.protocol.Hash
import org.alephium.protocol.model._
import org.alephium.rpc.CirceUtils
import org.alephium.rpc.model.JsonRPC._
import org.alephium.serde.serialize
import org.alephium.util._

class RPCServerSpec
    extends AlephiumSpec
    with NoIndexModelGenerators
    with ScalatestRouteTest
    with EitherValues
    with ScalaFutures
    with NumericHelpers {
  import ServerFixture._

  behavior of "http"

  it should "encode BlockNotify" in new ServerFixture {
    val dep         = Hash.hash("foo")
    val header      = BlockHeader(AVector(dep), Hash.hash("bar"), TimeStamp.zero, 1, 2)
    val blockNotify = BlockNotify(header, 1)
    val headerHash  = header.hash.toHexString
    val chainIndex  = header.chainIndex

    val result = RPCServer.blockNotifyEncode(blockNotify)

    show(result) is s"""{"hash":"$headerHash","timestamp":0,"chainFrom":${chainIndex.from.value},"chainTo":${chainIndex.to.value},"height":1,"deps":["${dep.toHexString}"]}"""
  }

  it should "call mining_start" in new RouteHTTP {
    checkCallResult("mining_start")(true)
    minerProbe.expectMsg(Miner.Start)
  }

  it should "call mining_stop" in new RouteHTTP {
    checkCallResult("mining_stop")(true)
    minerProbe.expectMsg(Miner.Stop)
  }

  it should "call self_clique_synced" in new RouteHTTP {
    checkCallResult("self_clique_synced")(true)
  }

  it should "call blockflow_fetch" in new RouteHTTP {
    checkCallResult("blockflow_fetch", parse("""{"fromTs":1, "toTs":42}""").toOption)(
      dummyFetchResponse)
  }

  it should "call neighbor_cliques" in new RouteHTTP {
    checkCallResult("neighbor_cliques")(dummyNeighborCliques)
  }

  it should "call self_clique" in new RouteHTTP {
    checkCallResult("self_clique")(dummySelfClique)
  }

  it should "call get_balance" in new RouteHTTP {
    checkCallResult("get_balance", parse(s"""{"address":"$dummyKeyAddress"}""").toOption)(
      dummyBalance)
  }

  it should "reject wrong get_balance call" in new RouteHTTP {
    checkFailCallResult("get_balance", parse(s"""{"address":"OOPS"}""").toOption)("Invalid params")
    checkFailCallResult(
      "get_balance",
      parse(s"""{"address":"$dummyKey","type":"OOPS"}""").toOption)("Invalid params")
    checkFailCallResult("get_balance", parse(s"""{"OOPS":"OOPS"}""").toOption)("Invalid params")
  }

  it should "call get_group" in new RouteHTTP {
    checkCallResult("get_group", parse(s"""{"address":"$dummyKeyAddress"}""").toOption)(dummyGroup)
  }

  it should "call get_hashes_at_height" in new RouteHTTP {
    checkCallResult(
      "get_hashes_at_height",
      parse(s"""{"fromGroup":"1","toGroup":"1","height":1}""").toOption)(dummyHashesAtHeight)
  }

  it should "call get_chain_info" in new RouteHTTP {
    checkCallResult("get_chain_info", parse(s"""{"fromGroup":"1","toGroup":"1"}""").toOption)(
      dummyChainInfo)
  }

  it should "call get_block" in new RouteHTTP {
    val chainIndex = ChainIndex.from(dummyBlockHeader.hash)
    if (config.brokerInfo.contains(chainIndex.from) || config.brokerInfo.contains(chainIndex.to)) {
      checkCallResult(
        "get_block",
        parse(s"""{"hash":"${dummyBlockHeader.hash.toHexString}"}""").toOption)(dummyBlockEntry)
    } else {
      checkFailCallResult(
        "get_block",
        parse(s"""{"hash":"${dummyBlockHeader.hash.toHexString}"}""").toOption)("Server error")
    }
  }

  it should "call send_transaction" in new RouteHTTP {
    checkCallResult(
      "send_transaction",
      parse(s"""{"tx":"${Hex
        .toHexString(serialize(dummyTx.unsigned))}","signature":"${dummySignature.toHexString}","publicKey":"$dummyKey"}""").toOption
    )(dummyTransferResult)
  }

  it should "call create_transaction" in new RouteHTTP {
    checkCallResult(
      "create_transaction",
      parse(s"""{"fromKey":"$dummyKey","toAddress":"$dummyToAddres","value":1}""").toOption
    )(dummyCreateTransactionResult)
  }

  it should "reject when address belongs to other groups" in new RouteHTTP {
    override val configValues = Map(
      ("alephium.broker.brokerId", 1)
    )
    checkFailCallResult("get_balance", parse(s"""{"address":"$dummyKeyAddress"}""").toOption)(
      "Server error")
  }
  it should "reject GET" in new RouteHTTP {
    Get() ~> server.httpRoute ~> check {
      rejections is List(
        MethodRejection(HttpMethods.POST)
      )
    }
  }

  it should "reject wrong content type" in new RouteHTTP {
    val entity  = HttpEntity(ContentTypes.`text/plain(UTF-8)`, CirceUtils.print(Json.Null))
    val request = HttpRequest(HttpMethods.POST, entity = entity)

    request ~> server.httpRoute ~> check {
      val List(rejection: UnsupportedRequestContentTypeRejection) = rejections
      rejection.supported is Set(ContentTypeRange(ContentTypes.`application/json`))
    }
  }

  it should "run/stop the server" in new RouteHTTP {
    server.start().futureValue is (())
    server.stop().futureValue is (())
  }

  it should "make sure rps and ws port are provided" in new ServerFixture {
    override val configValues = Map(
      ("alephium.network.rpcPort", 0),
      ("alephium.network.wsPort", 0)
    )

    val blockFlowProbe = TestProbe()

    val mode: Mode = new ModeDummy(dummyIntraCliqueInfo,
                                   dummyNeighborCliques,
                                   dummyBlock,
                                   blockFlowProbe.ref,
                                   dummyTx,
                                   storages)

    assertThrows[RuntimeException] {
      RPCServer(mode, ActorRefT(TestProbe().ref))
    }
  }

  behavior of "companion object"

  it should "safely handle `blockflowFetch` function" in new RPCServerFixture {
    val blockflowFetchMaxAge = Duration.ofMinutes(10).get
    def blockflowFetch(params: String) = {
      server.doBlockflowFetch(Request("blockflow_fetch", parse(params).toOption.get, 0)).futureValue
    }
    val invalidParams = Response.Failure(Error.InvalidParams, Some(0L))

    blockflowFetch(
      s"""{"fromTs":${(now - blockflowFetchMaxAge).get.millis},"toTs":${now.millis}}""")
    blockFlowProbe.expectMsg(true)

    blockflowFetch(s"""{"fromTs":${now.millis},"toTs":${now.millis}}""")
    blockFlowProbe.expectMsg(false)

    blockflowFetch("""{}""").left.value is invalidParams
    blockflowFetch("""{"fromTs":42,"toTs":1}""").left.value is invalidParams
    blockflowFetch("""{"fromTs":1,"toTs":1000000000}""").left.value is invalidParams
    blockflowFetch("""{"toTs":1}""").left.value is invalidParams
    blockflowFetch("""{"fromTs":1}""").left.value is invalidParams
  }

  behavior of "ws"

  it should "receive one event" in new RouteWS {
    checkWS {
      sendEventAndCheck
    }
  }

  it should "receive multiple events" in new RouteWS {
    checkWS {
      (0 to 3).foreach { _ =>
        sendEventAndCheck
      }
    }
  }

  it should "complete on `complete` command" in new AlephiumActorSpec("Websocket") {
    val (actorRef, source) = RPCServerAbstract.Websocket.actorRef
    val sinkProbe          = source.runWith(TestSink.probe[String])
    val message            = "Hello"
    actorRef ! message
    actorRef ! RPCServerAbstract.Websocket.Completed
    sinkProbe.request(1).expectNext(message).expectComplete()
  }

  it should "stop on `Failed` command" in new AlephiumActorSpec("Websocket") {
    val (actorRef, source) = RPCServerAbstract.Websocket.actorRef
    val sinkProbe          = source.runWith(TestSink.probe[String])
    val message            = "Hello"
    actorRef ! message
    actorRef ! RPCServerAbstract.Websocket.Failed
    sinkProbe.request(1).expectNextOrError() match {
      case Right(hello) => hello is message
      case Left(error)  => error.getMessage is "failure on events websocket"
    }
  }

  trait RPCServerFixture extends ServerFixture {
    val minerProbe = TestProbe()
    val miner      = ActorRefT[Miner.Command](minerProbe.ref)

    val blockFlowProbe = TestProbe()

    lazy val mode: Mode = new ModeDummy(dummyIntraCliqueInfo,
                                        dummyNeighborCliques,
                                        dummyBlock,
                                        blockFlowProbe.ref,
                                        dummyTx,
                                        storages)

    lazy val server: RPCServer = RPCServer(mode, miner)
  }

  trait RouteHTTP extends RPCServerFixture {
    implicit lazy val askTimeout                = Timeout(server.apiConfig.askTimeout.asScala)
    implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(2000, Millis))

    def checkCall[T](method: String, params: Option[Json])(f: Response.Success => T): T = {
      rpcRequest(method, params.getOrElse(Json.obj()), 0) ~> server.httpRoute ~> check {
        status is StatusCodes.OK
        f(responseAs[Response.Success])
      }
    }

    def checkCallResult[T: Decoder](method: String, params: Option[Json] = None)(
        expected: T): Assertion =
      checkCall(method, params)(json => json.result.as[T] isE expected)

    def checkFailCallResult(method: String, params: Option[Json] = None)(errorMessage: String) =
      rpcRequest(method, params.getOrElse(Json.obj()), 0) ~> server.httpRoute ~> check {
        responseAs[Response.Failure].error.message is errorMessage
      }

    def rpcRequest(method: String, params: Json, id: Long): HttpRequest = {
      // scalastyle:off regex
      val jsonRequest = Request(method, params, id).asJson.noSpaces
      val entity      = HttpEntity(MediaTypes.`application/json`, jsonRequest)
      // scalastyle:on

      HttpRequest(HttpMethods.POST, entity = entity)
    }
  }

  trait RouteWS extends RPCServerFixture {
    val client = WSProbe()

    val blockNotify = BlockNotify(blockGen.sample.get.header, height = 0)
    def sendEventAndCheck: Assertion = {
      mode.node.eventBus ! blockNotify
      val TextMessage.Strict(message) = client.expectMessage()

      val json         = parse(message).toOption.get
      val notification = json.as[NotificationUnsafe].toOption.get.asNotification.toOption.get

      notification.method is "block_notify"
    }

    def checkWS[A](f: => A): A =
      WS("/events", client.flow) ~> server.wsRoute ~> check {
        isWebSocketUpgrade is true
        f
      }
  }
}
