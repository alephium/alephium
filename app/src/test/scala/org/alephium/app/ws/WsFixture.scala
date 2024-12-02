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

package org.alephium.app.ws

import scala.concurrent.{ExecutionContext, Future}

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import akka.util.Timeout
import io.vertx.core.Vertx
import io.vertx.core.http.{HttpServerOptions, WebSocket, WebSocketClientOptions}
import org.scalatest.Assertion
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import sttp.tapir.server.vertx.VertxFutureServerInterpreter._

import org.alephium.app.{ApiConfig, ServerFixture}
import org.alephium.app.ServerFixture.NodeDummy
import org.alephium.app.ws.WsParams.{WsId, WsSubscriptionId}
import org.alephium.app.ws.WsSubscriptionHandler.{
  GetSubscriptions,
  SubscriptionMsg,
  SubscriptionsResponse
}
import org.alephium.flow.handler.TestUtils
import org.alephium.util._

trait WsServerFixture extends ServerFixture with ScalaFutures {

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

  def connectWebsocketClient(
      port: Int = node.config.network.restPort,
      host: String = "127.0.0.1",
      uri: String = "/ws"
  ): Future[WebSocket] =
    vertx
      .createWebSocketClient(new WebSocketClientOptions().setMaxFrameSize(1024 * 1024))
      .connect(port, host, uri)
      .asScala

  lazy val wsOptions =
    new HttpServerOptions()
      .setMaxWebSocketFrameSize(1024 * 1024)
      .setRegisterWebSocketWriteHandlers(true)

  def maxConnections: Int = 10

  def bindAndListen(): WsServer = {
    val wsServer =
      WsServer(
        system,
        node,
        maxConnections,
        wsOptions
      )
    wsServer.httpServer
      .listen(node.config.network.restPort, apiConfig.networkInterface.getHostAddress)
      .asScala
      .futureValue
    wsServer
  }

  def dummyServerWs(id: String): ServerWsLike = new ServerWsLike {
    override def textHandlerID(): WsId                                     = id
    override def isClosed: Boolean                                         = false
    override def reject(statusCode: Int): Unit                             = ()
    override def closeHandler(handler: () => Unit): ServerWsLike           = this
    override def textMessageHandler(handler: String => Unit): ServerWsLike = this
    override def writeTextMessage(msg: String)(implicit ec: ExecutionContext): Future[Unit] =
      Future.successful(())
  }

  def testSubscriptionHandlerInitialized(
      subscriptionHandler: ActorRefT[SubscriptionMsg]
  ): Assertion = {
    subscriptionHandler
      .ask(GetSubscriptions)
      .mapTo[SubscriptionsResponse]
      .futureValue
      .subscriptions
      .length is 0
  }

  def testEventHandlerInitialized(eventHandler: ActorRefT[EventBus.Message]): Assertion = {
    node.eventBus
      .ask(EventBus.ListSubscribers)
      .mapTo[EventBus.Subscribers]
      .futureValue
      .value
      .contains(eventHandler.ref) is true
  }

}

trait WsSubscriptionFixture extends WsServerFixture with Eventually {
  def getSubscriptions(
      subscriptionHandler: ActorRefT[WsSubscriptionHandler.SubscriptionMsg]
  ): SubscriptionsResponse =
    subscriptionHandler
      .ask(GetSubscriptions)
      .mapTo[SubscriptionsResponse]
      .futureValue

  def assertSubscribed(
      wsId: WsId,
      subscriptionId: WsSubscriptionId,
      subscriptionHandler: ActorRefT[WsSubscriptionHandler.SubscriptionMsg]
  ): Assertion = {
    getSubscriptions(subscriptionHandler).subscriptions
      .find(_._1 == wsId)
      .exists(_._2.filter(_._1 == subscriptionId).length == 1) is true
  }

  def assertConnectedButNotSubscribed(
      wsId: WsId,
      subscriptionId: WsSubscriptionId,
      subscriptionHandler: ActorRefT[WsSubscriptionHandler.SubscriptionMsg]
  ): Assertion = {
    getSubscriptions(subscriptionHandler).subscriptions
      .find(_._1 == wsId)
      .exists(_._2.filter(_._1 == subscriptionId).length == 0) is true
  }

  def assertNotConnected(
      wsId: WsId,
      subscriptionHandler: ActorRefT[WsSubscriptionHandler.SubscriptionMsg]
  ): Assertion = {
    getSubscriptions(subscriptionHandler).subscriptions
      .find(_._1 == wsId)
      .isEmpty is true
  }

}

trait WsBehaviorFixture extends WsServerFixture with Eventually with IntegrationPatience {
  import org.alephium.app.ws.WsSubscriptionHandler._
  import org.alephium.app.ws.WsBehaviorFixture._

  def checkWS(
      initBehaviors: AVector[WsStartBehavior],
      nextBehaviors: AVector[WsNextBehavior],
      expectedSubscriptions: Int,
      openWebsocketsCount: Int
  ): Unit = {
    val WsServer(httpServer, eventHandler, subscriptionHandler) = bindAndListen()

    eventually(testSubscriptionHandlerInitialized(subscriptionHandler))
    eventually(testEventHandlerInitialized(eventHandler))

    val probedSockets =
      initBehaviors.map { case WsStartBehavior(startBehavior, _, _) =>
        val ws          = connectWebsocketClient().futureValue
        val clientProbe = TestProbe()
        startBehavior(ws, clientProbe)
        ws -> clientProbe
      }

    initBehaviors.foreach { case WsStartBehavior(_, serverBehavior, clientAssertionOnMsg) =>
      serverBehavior(node.eventBus)
      probedSockets.foreach { case (_, clientProbe) =>
        clientAssertionOnMsg(clientProbe)
      }
    }
    probedSockets.foreach { case (ws, clientProbe) =>
      nextBehaviors.foreach { case WsNextBehavior(behavior, serverBehavior, clientAssertionOnMsg) =>
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
    httpServer.close().asScala.mapTo[Unit].futureValue
  }
}

object WsBehaviorFixture {

  sealed trait WsBehavior
  final case class WsStartBehavior(
      clientInitBehavior: (WebSocket, TestProbe) => Unit,
      serverBehavior: ActorRefT[EventBus.Message] => Unit,
      clientAssertionOnMsg: TestProbe => Any
  ) extends WsBehavior

  final case class WsNextBehavior(
      clientInitBehavior: WebSocket => Unit,
      serverBehavior: ActorRefT[EventBus.Message] => Unit,
      clientAssertionOnMsg: TestProbe => Any
  ) extends WsBehavior

}
