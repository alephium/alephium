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
import akka.testkit.TestProbe
import io.vertx.core.Vertx
import org.scalatest.{Assertion, EitherValues}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Minutes, Span}
import sttp.tapir.server.vertx.VertxFutureServerInterpreter._

import org.alephium.flow.handler.FlowHandler.BlockNotify
import org.alephium.json.Json._
import org.alephium.protocol.{BlockHash, Hash}
import org.alephium.protocol.model._
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util._

class WebSocketServerSpec
    extends AlephiumSpec
    with NoIndexModelGenerators
    with EitherValues
    with ScalaFutures
    with NumericHelpers {
  import ServerFixture._

  implicit override val patienceConfig = PatienceConfig(timeout = Span(1, Minutes))

  behavior of "http"

  it should "encode BlockNotify" in new ServerFixture {
    val dep  = BlockHash.hash("foo")
    val deps = AVector.fill(groupConfig.depsNum)(dep)
    val header =
      BlockHeader.unsafe(deps, Hash.zero, Hash.hash("bar"), TimeStamp.zero, Target.Max, 2)
    val blockNotify = BlockNotify(header, 1)
    val headerHash  = header.hash.toHexString
    val chainIndex  = header.chainIndex

    val result = WebSocketServer.blockNotifyEncode(blockNotify)

    val depsString = AVector.fill(groupConfig.depsNum)(s""""${dep.toHexString}"""").mkString(",")
    show(
      result
    ) is s"""{"hash":"$headerHash","timestamp":0,"chainFrom":${chainIndex.from.value},"chainTo":${chainIndex.to.value},"height":1,"deps":[$depsString]}"""
  }

  behavior of "ws"

  it should "receive one event" in new RouteWS {
    checkWS {
      sendEventAndCheck
    }
  }

  it should "receive multiple events" in new RouteWS {
    checkWS {
      (0 to 3).foreach { _ => sendEventAndCheck }
    }
  }

  trait WebSocketServerFixture extends ServerFixture {

    implicit val executionContext: ExecutionContext = ExecutionContext.Implicits.global
    implicit val system: ActorSystem                = ActorSystem("websocket-server-spec")
    lazy val blockFlowProbe                         = TestProbe()
    lazy val node = new NodeDummy(
      dummyIntraCliqueInfo,
      dummyNeighborPeers,
      dummyBlock,
      blockFlowProbe.ref,
      dummyTx,
      storages
    )
    lazy val server: WebSocketServer = WebSocketServer(node)
  }

  trait RouteWS extends WebSocketServerFixture {

    private val vertx      = Vertx.vertx()
    private val httpClient = vertx.createHttpClient()
    val port               = node.config.network.wsPort
    val blockNotifyProbe   = TestProbe()

    val blockNotify = BlockNotify(blockGen.sample.get.header, height = 0)
    def sendEventAndCheck: Assertion = {
      node.eventBus ! blockNotify

      blockNotifyProbe.expectMsgPF() { case message: String =>
        val notification = read[NotificationUnsafe](message).asNotification.toOption.get
        notification.method is "block_notify"
      }
    }

    def checkWS[A](f: => A): A =
      try {
        server.start().futureValue

        httpClient
          .webSocket(port, "localhost", "/events")
          .asScala
          .map { ws =>
            ws.textMessageHandler { blockNotify =>
              blockNotifyProbe.ref ! blockNotify
            }
          }
          .futureValue

        f
      } finally {
        server.stop().futureValue
      }
  }
}
