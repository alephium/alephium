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

import scala.concurrent.ExecutionContext

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.testkit.TestProbe
import akka.util.Timeout
import io.vertx.core.Vertx
import io.vertx.core.http.{HttpServerOptions, WebSocket, WebSocketClientOptions}
import org.scalatest.Assertion
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import sttp.tapir.server.vertx.VertxFutureServerInterpreter._

import org.alephium.app.ServerFixture.NodeDummy
import org.alephium.flow.handler.TestUtils
import org.alephium.util._

trait WebSocketServerFixture extends ServerFixture {

  implicit lazy val apiConfig: ApiConfig          = ApiConfig.load(newConfig)
  override val configValues                       = configPortsValues
  implicit val system: ActorSystem                = ActorSystem("websocket-server-spec")
  implicit val executionContext: ExecutionContext = system.dispatcher
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
  lazy val WebSocketServer(httpServer, eventHandler) =
    WebSocketServer(
      system,
      node,
      maxConnections = 100,
      new HttpServerOptions()
        .setMaxWebSocketFrameSize(1024 * 1024)
        .setRegisterWebSocketWriteHandlers(true)
    )
}

trait RouteWS extends WebSocketServerFixture with Eventually with ScalaFutures {
  private val vertx = Vertx.vertx()
  private val webSocketClient =
    vertx.createWebSocketClient(new WebSocketClientOptions().setMaxFrameSize(1024 * 1024))
  private val port = node.config.network.restPort

  def behavior(probe: TestProbe)(ws: WebSocket): WebSocket

  def checkWS(wsCount: Int, causeEffectList: Iterable[(EventBus.Event, String => Assertion)]) = {
    val binding =
      httpServer.listen(port, apiConfig.networkInterface.getHostAddress).asScala.futureValue

    implicit val timeout: Timeout = Timeout(Duration.ofSecondsUnsafe(5).asScala)
    eventually {
      node.eventBus
        .ask(EventBus.ListSubscribers)
        .mapTo[EventBus.Subscribers]
        .futureValue
        .value
        .contains(eventHandler) is true
    }

    val probedSockets =
      AVector.fill(wsCount) {
        val probe = TestProbe()
        probe -> webSocketClient
          .connect(port, "127.0.0.1", "/ws")
          .asScala
          .map(behavior(probe))
          .futureValue
      }

    eventually {
      eventHandler
        .ask(WebSocketServer.EventHandler.ListSubscribers)
        .mapTo[AVector[String]]
        .futureValue
        .length is probedSockets.length
    }
    causeEffectList.foreach { case (cause, effect) =>
      node.eventBus ! cause
      probedSockets.foreach { case (probe, _) =>
        probe.expectMsgPF() { case message: String =>
          effect(message)
        }
      }
    }

    probedSockets.foreach(_._2.close().asScala.futureValue)
    binding.close().asScala
  }
}
