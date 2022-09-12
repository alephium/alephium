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
import io.vertx.core.http.HttpClientOptions
import org.scalatest.{Assertion, EitherValues}
import sttp.tapir.server.vertx.VertxFutureServerInterpreter._

import org.alephium.api.model.BlockEntry
import org.alephium.crypto.Blake3
import org.alephium.flow.handler.FlowHandler.BlockNotify
import org.alephium.flow.handler.TestUtils
import org.alephium.json.Json._
import org.alephium.protocol.Hash
import org.alephium.protocol.model._
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util._

class WebSocketServerSpec
    extends AlephiumFutureSpec
    with NoIndexModelGenerators
    with EitherValues
    with NumericHelpers {
  import ServerFixture._

  behavior of "http"

  it should "encode BlockNotify" in new Fixture {
    val dep  = BlockHash.unsafe(Blake3.hash("foo"))
    val deps = AVector.fill(groupConfig.depsNum)(dep)
    val header =
      BlockHeader.unsafeWithRawDeps(
        deps,
        Hash.zero,
        Hash.hash("bar"),
        TimeStamp.zero,
        Target.Max,
        Nonce.zero
      )

    val block       = Block(header, AVector.empty)
    val blockNotify = BlockNotify(block, 1)
    val result      = WebSocketServer.blockNotifyEncode(blockNotify)

    show(
      result
    ) is write(BlockEntry.from(block, 1))
  }

  behavior of "ws"

  it should "receive multiple events" in new RouteWS {
    checkWS {
      (0 to 3).foreach { _ => sendEventAndCheck }
    }
  }

  trait Fixture extends ServerFixture {

    override val configValues = configPortsValues

    implicit lazy val apiConfig: ApiConfig = ApiConfig.load(newConfig)
    lazy val blockflowFetchMaxAge          = apiConfig.blockflowFetchMaxAge
  }

  trait WebSocketServerFixture extends Fixture {

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
    lazy val server: WebSocketServer = WebSocketServer(node)
  }

  trait RouteWS extends WebSocketServerFixture {

    private val vertx = Vertx.vertx()
    private val httpClient = {
      val options = new HttpClientOptions().setMaxWebSocketFrameSize(1024 * 1024)
      vertx.createHttpClient(options)
    }
    val port             = node.config.network.wsPort
    val blockNotifyProbe = TestProbe()

    val blockNotify = BlockNotify(blockGen.sample.get, height = 0)
    def sendEventAndCheck: Assertion = {
      node.eventBus ! blockNotify

      blockNotifyProbe.expectMsgPF() { case message: String =>
        val notification = read[NotificationUnsafe](message).asNotification.rightValue
        notification.method is "block_notify"
      }
    }

    def checkWS[A](f: => A) = {
      server.start().futureValue

      implicit val timeout: Timeout = Timeout(Duration.ofSecondsUnsafe(5).asScala)
      eventually {
        node.eventBus
          .ask(EventBus.ListSubscribers)
          .mapTo[EventBus.Subscribers]
          .futureValue
          .value
          .contains(server.eventHandler) is true
      }

      val ws = httpClient
        .webSocket(port, "127.0.0.1", "/events")
        .asScala
        .map { ws =>
          ws.textMessageHandler { blockNotify =>
            blockNotifyProbe.ref ! blockNotify
          }
          ws
        }
        .futureValue

      eventually {
        server.eventHandler
          .ask(WebSocketServer.EventHandler.ListSubscribers)
          .mapTo[AVector[String]]
          .futureValue
          .nonEmpty is true
      }

      f

      ws.close().asScala.futureValue
      server.stop().futureValue
    }
  }
}
