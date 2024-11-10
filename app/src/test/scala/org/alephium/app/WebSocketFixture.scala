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
import io.vertx.core.http.{HttpServerOptions, WebSocket, WebSocketClientOptions}
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import sttp.tapir.server.vertx.VertxFutureServerInterpreter._

import org.alephium.app.ServerFixture.NodeDummy
import org.alephium.flow.handler.TestUtils
import org.alephium.util._

trait WebSocketServerFixture extends ServerFixture {

  implicit lazy val apiConfig: ApiConfig          = ApiConfig.load(newConfig)
  implicit val timeout: Timeout                   = Timeout(Duration.ofSecondsUnsafe(5).asScala)
  override val configValues                       = configPortsValues
  implicit val system: ActorSystem                = ActorSystem("websocket-server-spec")
  implicit val executionContext: ExecutionContext = system.dispatcher
  lazy val vertx                                  = Vertx.vertx()
  lazy val blockFlowProbe                         = TestProbe()
  val (allHandlers, _)                            = TestUtils.createAllHandlersProbe
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

  def maxConnections: Int = 10

  lazy val WebSocketServer(httpServer, eventHandler, subscribers) =
    WebSocketServer(
      system,
      node,
      maxConnections,
      new HttpServerOptions()
        .setMaxWebSocketFrameSize(1024 * 1024)
        .setRegisterWebSocketWriteHandlers(true)
    )
}

final case class WebSocketSpec(
    clientInitBehavior: (WebSocket, TestProbe) => (WebSocket, TestProbe),
    serverBehavior: ActorRefT[EventBus.Message] => Unit,
    clientAssertionOnMsg: TestProbe => Any
)
trait RouteWS
    extends WebSocketServerFixture
    with Eventually
    with ScalaFutures
    with IntegrationPatience {
  private val port = node.config.network.restPort

  private def newWebSocketClient =
    vertx.createWebSocketClient(new WebSocketClientOptions().setMaxFrameSize(1024 * 1024))

  def checkWS(behaviorCauseEffectList: AVector[WebSocketSpec]) = {
    val binding =
      httpServer.listen(port, apiConfig.networkInterface.getHostAddress).asScala.futureValue

    eventually {
      node.eventBus
        .ask(EventBus.ListSubscribers)
        .mapTo[EventBus.Subscribers]
        .futureValue
        .value
        .contains(eventHandler) is true
    }

    // run defined Behavior of websocket clients like : Connecting and Subscribing
    val probedSockets =
      Future
        .sequence(
          behaviorCauseEffectList.map { case WebSocketSpec(clientInitBehavior, _, _) =>
            newWebSocketClient
              .connect(port, "127.0.0.1", "/ws")
              .asScala
              .map(ws => clientInitBehavior(ws, TestProbe()))
          }.toSeq
        )
        .futureValue

    eventually {
      subscribers.entries().map(_.getKey).size is probedSockets.length
    }

    // run defined Cause of websocket server with Effect on websocket client
    behaviorCauseEffectList.foreach { case WebSocketSpec(_, serverBehavior, clientAssertionOnMsg) =>
      serverBehavior(node.eventBus)
      probedSockets.foreach { case (_, clientProbe) =>
        clientAssertionOnMsg(clientProbe)
      }
    }

    probedSockets.foreach(_._1.close().asScala.futureValue)
    binding.close().asScala.futureValue
  }
}
