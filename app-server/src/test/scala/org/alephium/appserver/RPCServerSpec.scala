package org.alephium.appserver

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.server.{MethodRejection, Route, UnsupportedRequestContentTypeRejection}
import akka.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import akka.testkit.TestProbe
import akka.util.Timeout
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.{Decoder, Encoder, Json, JsonObject}
import io.circe.parser._
import io.circe.syntax._
import org.scalatest.{Assertion, EitherValues}

import org.alephium.appserver.RPCModel._
import org.alephium.crypto.Keccak256
import org.alephium.flow.client.Miner
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.core.FlowHandler.BlockNotify
import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.model.{BlockHeader, ModelGen}
import org.alephium.rpc.CirceUtils
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util.{ActorRefT, AlephiumSpec, AVector, Duration, EventBus, TimeStamp}

object RPCServerSpec {
  import RPCServerAbstract.FutureTry

  val jsonObjectEmpty = JsonObject.empty.asJson

  def show[T](t: T)(implicit encoder: Encoder[T]): String = {
    CirceUtils.print(t.asJson)
  }

  case object Dummy extends EventBus.Event

  class RPCServerDummy(implicit val config: PlatformConfig) extends RPCServerAbstract {
    implicit val system: ActorSystem                = ActorSystem()
    implicit val executionContext: ExecutionContext = system.dispatcher
    implicit val rpcConfig: RPCConfig               = RPCConfig.load(config.aleph)
    implicit val askTimeout: Timeout                = Timeout(rpcConfig.askTimeout.asScala)

    def successful[T](t: T): FutureTry[T] = Future.successful(Right(t))

    val dummyFetchResponse   = FetchResponse(Seq.empty)
    val dummySelfClique      = SelfClique(AVector.empty, 1)
    val dummyNeighborCliques = NeighborCliques(AVector.empty)
    val dummyBalance         = Balance(1, 1)
    val dummyGroup           = Group(42)
    val dummyTransferResult  = TransferResult("foobar")

    def doBlockflowFetch(req: Request): FutureTry[FetchResponse] = successful(dummyFetchResponse)
    def doGetSelfClique(req: Request): FutureTry[SelfClique]     = successful(dummySelfClique)
    def doGetNeighborCliques(req: Request): FutureTry[NeighborCliques] =
      successful(dummyNeighborCliques)
    def doGetBalance(req: Request): FutureTry[Balance]      = successful(dummyBalance)
    def doGetGroup(req: Request): FutureTry[Group]      = successful(dummyGroup)
    def doTransfer(req: Request): FutureTry[TransferResult] = successful(dummyTransferResult)
    def doBlockNotify(blockNotify: BlockNotify): Json       = Json.Null

    def runServer(): Future[Unit] = Future.successful(())

    override def handleEvent(event: EventBus.Event): TextMessage = {
      event match {
        case _ =>
          val result = Notification("events_fake", jsonObjectEmpty)
          TextMessage(show(result))
      }
    }

  }
}

class RPCServerSpec extends AlephiumSpec with ScalatestRouteTest with EitherValues { Spec =>
  import RPCServerSpec._

  behavior of "RPCServer"

  implicit val config: PlatformConfig = PlatformConfig.loadDefault()

  val rpcSuccess    = Response.Success(Json.fromInt(42), 1)
  val rpcSuccessRaw = """{"jsonrpc":"2.0","result":42,"id":1}"""

  trait RouteHTTP {
    implicit lazy val askTimeout = Timeout(server.rpcConfig.askTimeout.asScala)

    lazy val server: RPCServerDummy = new RPCServerDummy {}
    lazy val route: Route           = server.routeHttp(TestProbe().ref)

    def checkCall[T](method: String)(f: Response.Success => T): T = {
      rpcRequest(method, Json.obj(), 0) ~> route ~> check {
        status is StatusCodes.OK
        f(responseAs[Response.Success])
      }
    }

    def checkCallResult[T: Decoder](method: String)(expected: T): Assertion =
      checkCall(method)(json => json.result.as[T].right.value is expected)

    def rpcRequest(method: String, params: Json, id: Long): HttpRequest = {
      // scalastyle:off regex
      val jsonRequest = Request(method, params, id).asJson.noSpaces
      val entity      = HttpEntity(MediaTypes.`application/json`, jsonRequest)
      // scalastyle:on

      HttpRequest(HttpMethods.POST, entity = entity)
    }
  }

  trait MiningMock extends RouteHTTP {
    val miner                      = TestProbe()
    override lazy val route: Route = server.routeHttp(miner.ref)
  }

  trait RouteWS {
    val client = WSProbe()
    val server = new RPCServerDummy {}
    val eventBus =
      ActorRefT.build[EventBus.Message](system, EventBus.props(), s"EventBus-${Random.nextInt}")
    val route = server.routeWs(eventBus)

    def sendEventAndCheck: Assertion = {
      eventBus ! Dummy
      val TextMessage.Strict(message) = client.expectMessage()

      val json         = parse(message).right.value
      val notification = json.as[NotificationUnsafe].right.value.asNotification.right.value

      notification.method is "events_fake"
    }

    def checkWS[A](f: => A): A =
      WS("/events", client.flow) ~> route ~> check {
        isWebSocketUpgrade is true
        f
      }
  }

  behavior of "http"

  it should "encode BlockNotify" in {
    val header =
      BlockHeader(AVector(Keccak256.hash("foo")), Keccak256.hash("bar"), TimeStamp.zero, 1, 2)
    val notify = BlockNotify(header, 1)

    val result = RPCServer.blockNotifyEncode(notify)

    show(result) is """{"hash":"62c38e6d","timestamp":0,"chainFrom":0,"chainTo":2,"height":1,"deps":["de098c4d"]}"""
  }

  it should "call mining_start" in new MiningMock {
    checkCallResult("mining_start")(true)
    miner.expectMsg(Miner.Start)
  }

  it should "call mining_stop" in new MiningMock {
    checkCallResult("mining_stop")(true)
    miner.expectMsg(Miner.Stop)
  }

  it should "call blockflow_fetch" in new RouteHTTP {
    checkCallResult("blockflow_fetch")(server.dummyFetchResponse)
  }

  it should "call neighbor_cliques" in new RouteHTTP {
    checkCallResult("neighbor_cliques")(server.dummyNeighborCliques)
  }

  it should "call self_clique" in new RouteHTTP {
    checkCallResult("self_clique")(server.dummySelfClique)
  }

  it should "call get_balance" in new RouteHTTP {
    checkCallResult("get_balance")(server.dummyBalance)
  }

  it should "call get_group" in new RouteHTTP {
    checkCallResult("get_group")(server.dummyGroup)
  }

  it should "call transfer" in new RouteHTTP {
    checkCallResult("transfer")(server.dummyTransferResult)
  }

  it should "reject GET" in new RouteHTTP {
    Get() ~> route ~> check {
      rejections is List(
        MethodRejection(HttpMethods.OPTIONS),
        MethodRejection(HttpMethods.POST)
      )
    }
  }

  it should "reject wrong content type" in new RouteHTTP {
    val entity  = HttpEntity(ContentTypes.`text/plain(UTF-8)`, CirceUtils.print(Json.Null))
    val request = HttpRequest(HttpMethods.POST, entity = entity)

    request ~> route ~> check {
      val List(rejection: UnsupportedRequestContentTypeRejection) = rejections
      rejection.supported is Set(ContentTypeRange(ContentTypes.`application/json`))
    }
  }

  behavior of "companion object"

  it should "safely handle `blockflowFetch` function" in {
    val dummyAddress         = ModelGen.socketAddress.sample.get
    val now                  = TimeStamp.now()
    val blockflowFetchMaxAge = Duration.ofMinutes(10).get
    val blockHeader =
      ModelGen.blockGen.sample.get.header.copy(timestamp = (now - Duration.ofMinutes(5).get).get)

    class BlockFlowDummy(probe: ActorRef)(implicit config: PlatformConfig) extends BlockFlow {
      override def getHeadersUnsafe(predicate: BlockHeader => Boolean): Seq[BlockHeader] = {
        probe ! predicate(blockHeader)
        Seq.empty
      }
    }

    implicit val rpcConfig: RPCConfig =
      RPCConfig(dummyAddress.getAddress, blockflowFetchMaxAge, askTimeout = Duration.zero)
    implicit val fetchRequestDecoder: Decoder[FetchRequest] = FetchRequest.decoder

    val blockFlowProbe = TestProbe()
    val blockFlow      = new BlockFlowDummy(blockFlowProbe.ref)
    def blockflowFetch(params: String) = {
      RPCServer.blockflowFetch(blockFlow, Request("blockflow_fetch", parse(params).right.value, 0))
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
}
