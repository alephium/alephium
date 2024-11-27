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

package org.alephium.app

import scala.concurrent.{ExecutionContext, Future}

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import akka.util.Timeout
import io.vertx.core.Vertx
import io.vertx.core.http.{HttpServer, HttpServerOptions, WebSocket, WebSocketClientOptions}
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import sttp.tapir.server.vertx.VertxFutureServerInterpreter._

import org.alephium.app.ServerFixture.NodeDummy
import org.alephium.app.WsSubscriptionHandler.{GetSubscriptions, SubscriptionsResponse}
import org.alephium.flow.handler.TestUtils
import org.alephium.util._

trait WebSocketServerFixture extends ServerFixture with ScalaFutures {

  implicit lazy val apiConfig: ApiConfig          = ApiConfig.load(newConfig)
  implicit val timeout: Timeout                   = Timeout(Duration.ofSecondsUnsafe(5).asScala)
  override val configValues                       = configPortsValues
  implicit val system: ActorSystem                = ActorSystem("websocket-server-spec")
  implicit val executionContext: ExecutionContext = system.dispatcher
  lazy val vertx                                  = Vertx.vertx()
  lazy val blockFlowProbe                         = TestProbe()
  lazy val (allHandlers, _)                       = TestUtils.createAllHandlersProbe
  lazy val node = new NodeDummy(
    dummyIntraCliqueInfo,
    dummyNeighborPeers,
    dummyBlock,
    blockFlowProbe.ref,
    allHandlers,
    dummyTx,
    dummyContract,
    storages
  )

  def connectWebsocketClient: Future[WebSocket] =
    vertx
      .createWebSocketClient(new WebSocketClientOptions().setMaxFrameSize(1024 * 1024))
      .connect(node.config.network.restPort, "127.0.0.1", "/ws")
      .asScala

  def maxConnections: Int = 10

  lazy val wsOptions =
    new HttpServerOptions()
      .setMaxWebSocketFrameSize(1024 * 1024)
      .setRegisterWebSocketWriteHandlers(true)

  lazy val WebSocketServer(httpServer, eventHandler, subscriptionHandler) =
    WebSocketServer(
      system,
      node,
      maxConnections,
      wsOptions
    )

  def bindAndListen(): HttpServer = httpServer
    .listen(node.config.network.restPort, apiConfig.networkInterface.getHostAddress)
    .asScala
    .futureValue
}

final case class WsStartBehavior(
    clientInitBehavior: (WebSocket, TestProbe) => Future[Unit],
    serverBehavior: ActorRefT[EventBus.Message] => Unit,
    clientAssertionOnMsg: TestProbe => Any
)
final case class WsBehavior(
    clientInitBehavior: WebSocket => Future[Unit],
    serverBehavior: ActorRefT[EventBus.Message] => Unit,
    clientAssertionOnMsg: TestProbe => Any
)
trait RouteWS extends WebSocketServerFixture with Eventually with IntegrationPatience {

  private def assertEventHandlerSubscribed = {
    node.eventBus
      .ask(EventBus.ListSubscribers)
      .mapTo[EventBus.Subscribers]
      .futureValue
      .value
      .contains(eventHandler.ref) is true
  }

  def checkWS(
      initBehaviors: AVector[WsStartBehavior],
      nextBehaviors: AVector[WsBehavior],
      expectedSubscriptions: Int,
      openWebsocketsCount: Int
  ) = {
    val httpBinding = bindAndListen()
    eventually(assertEventHandlerSubscribed)
    val probedSockets =
      Future
        .sequence(
          initBehaviors.map { case WsStartBehavior(startBehavior, _, _) =>
            connectWebsocketClient
              .map { ws =>
                val clientProbe = TestProbe()
                startBehavior(ws, clientProbe)
                ws -> clientProbe
              }
          }.toSeq
        )
        .futureValue

    initBehaviors.foreach { case WsStartBehavior(_, serverBehavior, clientAssertionOnMsg) =>
      serverBehavior(node.eventBus)
      probedSockets.foreach { case (_, clientProbe) =>
        clientAssertionOnMsg(clientProbe)
      }
    }
    probedSockets.foreach { case (ws, clientProbe) =>
      nextBehaviors.foreach { case WsBehavior(behavior, serverBehavior, clientAssertionOnMsg) =>
        behavior(ws)
        serverBehavior(node.eventBus)
        clientAssertionOnMsg(clientProbe)
      }
    }
    eventually {
      probedSockets.filterNot(_._1.isClosed).length is openWebsocketsCount
      subscriptionHandler
        .ask(GetSubscriptions)
        .mapTo[SubscriptionsResponse]
        .futureValue
        .subscriptions
        .map(_._2.length)
        .sum is expectedSubscriptions
    }
    probedSockets.foreach(_._1.close().asScala.futureValue)
    httpBinding.close().asScala.futureValue
  }
}
