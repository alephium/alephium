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
import io.vertx.core.http.WebSocketClientOptions
import org.scalatest.{Assertion, EitherValues}
import sttp.tapir.server.vertx.VertxFutureServerInterpreter._

import org.alephium.api.model.{BlockEntry, GhostUncleBlockEntry}
import org.alephium.crypto.Blake3
import org.alephium.flow.handler.FlowHandler.BlockNotify
import org.alephium.flow.handler.TestUtils
import org.alephium.json.Json._
import org.alephium.protocol.{Hash, PublicKey}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.LockupScript
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util._

class HttpServerWithWebSocketSpec
    extends AlephiumFutureSpec
    with NoIndexModelGenerators
    with EitherValues
    with NumericHelpers {
  import ServerFixture._

  behavior of "http"

  it should "encode BlockEntry" in new ServerFixture {
    val dep  = BlockHash.unsafe(Blake3.hash("foo"))
    val deps = AVector.fill(groupConfig.depsNum)(dep)
    val blockEntry = BlockEntry(
      BlockHash.random,
      TimeStamp.zero,
      0,
      0,
      1,
      deps,
      AVector.empty,
      Nonce.zero.value,
      0,
      Hash.zero,
      Hash.hash("bar"),
      Target.Max.bits,
      AVector(
        GhostUncleBlockEntry(
          BlockHash.random,
          Address.Asset(LockupScript.p2pkh(PublicKey.generate))
        )
      )
    )
    val result = writeJs(blockEntry)
    show(result) is write(blockEntry)
  }

  behavior of "ws"

  it should "receive multiple events by multiple websockets" in new RouteWS {
    checkWS(
      10,
      (0 to 10).map { _ =>
        (
          BlockNotify(blockGen.sample.get, height = 0),
          probeMsg =>
            read[NotificationUnsafe](probeMsg).asNotification.rightValue.method is "block_notify"
        )
      }
    )
  }

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
    lazy val HttpServerWithWebSocket(httpServer, eventHandler) =
      HttpServerWithWebSocket(system, node)
  }

  trait RouteWS extends WebSocketServerFixture {

    private val vertx = Vertx.vertx()
    private val webSocketClient = {
      val options = new WebSocketClientOptions().setMaxFrameSize(1024 * 1024)
      vertx.createWebSocketClient(options)
    }
    val port = node.config.network.wsPort

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
            .connect(port, "127.0.0.1", "/ws/events")
            .asScala
            .map { ws =>
              ws.textMessageHandler { blockNotify =>
                probe.ref ! blockNotify
              }
              ws
            }
            .futureValue
        }

      eventually {
        eventHandler
          .ask(HttpServerWithWebSocket.EventHandler.ListSubscribers)
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
}
