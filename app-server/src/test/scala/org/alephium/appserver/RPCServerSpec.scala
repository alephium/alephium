package org.alephium.appserver

import scala.concurrent.{ExecutionContext, Future}

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.server.{MethodRejection, Route, UnsupportedRequestContentTypeRejection}
import akka.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import akka.stream.ActorMaterializer
import akka.testkit.TestProbe
import akka.util.Timeout
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.Json
import io.circe.parser._
import io.circe.syntax._
import org.scalatest.{Assertion, EitherValues}

import org.alephium.flow.client.Miner
import org.alephium.flow.platform.PlatformProfile
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util.{AlephiumSpec, EventBus}

object RPCServerSpec {
  case object Dummy extends EventBus.Event

  class RPCServerDummy(implicit val config: PlatformProfile) extends RPCServerAbstract {
    implicit val system: ActorSystem                = ActorSystem()
    implicit val materializer: ActorMaterializer    = ActorMaterializer()
    implicit val executionContext: ExecutionContext = system.dispatcher
    implicit val rpcConfig: RPCConfig               = RPCConfig.load(config.aleph)
    implicit val askTimeout: Timeout                = Timeout(rpcConfig.askTimeout.asScala)

    def doBlockflowFetch(req: Request): Future[Response] =
      Future.successful(Response.failed(Error.server("test")))
    def doCliqueInfo(req: Request): Future[Response] =
      Future.successful(Response.failed(Error.server("test")))
    def doGetBalance(req: Request): Future[Response] =
      Future.successful(Response.failed(Error.server("test")))

    def runServer(): Future[Unit] = Future.successful(())
  }
}

class RPCServerSpec extends AlephiumSpec with ScalatestRouteTest with EitherValues { Spec =>
  import RPCServerSpec._

  behavior of "RPCServer"

  implicit val config: PlatformProfile = PlatformProfile.loadDefault()

  val rpcSuccess = Response.Success(Json.fromInt(42), 1)

  trait RouteHTTP {
    implicit lazy val askTimeout = Timeout(server.rpcConfig.askTimeout.asScala)

    lazy val server: RPCServerDummy = new RPCServerDummy {}
    lazy val route: Route           = server.routeHttp(TestProbe().ref)

    def checkCall[T](method: String)(f: Response.Success => T): T = {
      rpcRequest(method, Json.obj(), 0) ~> route ~> check {
        status.intValue is 200
        f(responseAs[Response.Success])
      }
    }

    def checkCallResult[T](method: String)(f: Json => T): T =
      checkCall(method)(json => f(json.result))

    def rpcRequest(method: String, params: Json, id: Long): HttpRequest = {
      val jsonRequest = Request(method, Some(params), id).asJson.noSpaces
      HttpRequest(HttpMethods.POST,
                  "/",
                  entity = HttpEntity(MediaTypes.`application/json`, jsonRequest))
    }
  }

  trait MiningMock extends RouteHTTP {
    val miner                      = TestProbe()
    override lazy val route: Route = server.routeHttp(miner.ref)
  }

  trait RouteWS {
    val client   = WSProbe()
    val server   = new RPCServerDummy {}
    val eventBus = system.actorOf(EventBus.props())
    val route    = server.routeWs(eventBus)

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

  it should "call mining_start" in new MiningMock {
    checkCallResult("mining_start") { result =>
      miner.expectMsg(Miner.Start)
      result.as[Boolean].right.value is true
    }
  }

  it should "call mining_stop" in new MiningMock {
    checkCallResult("mining_stop") { result =>
      miner.expectMsg(Miner.Stop)
      result.as[Boolean].right.value is true
    }
  }

  it should "call blockflow_fetch" in new RouteHTTP {
    override lazy val server = new RPCServerDummy {
      override def doBlockflowFetch(req: Request): Future[Response] =
        Future.successful(rpcSuccess)
    }

    checkCall("blockflow_fetch") { response =>
      response is rpcSuccess
    }
  }

  it should "call clique_info" in new RouteHTTP {
    override lazy val server = new RPCServerDummy {
      override def doCliqueInfo(req: Request): Future[Response] =
        Future.successful(rpcSuccess)
    }

    checkCall("clique_info") { response =>
      response is rpcSuccess
    }
  }

  it should "call get_balance" in new RouteHTTP {
    override lazy val server = new RPCServerDummy {
      override def doGetBalance(req: Request): Future[Response] = {
        Future.successful(rpcSuccess)
      }
    }

    checkCall("get_balance") { response =>
      response is rpcSuccess
    }
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
    val request = HttpRequest(HttpMethods.POST,
                              "/",
                              entity =
                                HttpEntity(ContentTypes.`text/plain(UTF-8)`, Json.Null.noSpaces))

    request ~> route ~> check {
      val List(rejection: UnsupportedRequestContentTypeRejection) = rejections
      rejection.supported is Set(ContentTypeRange(ContentTypes.`application/json`))
    }
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
