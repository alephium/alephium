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

import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestProbe
import org.scalatest.{Assertion, EitherValues}
import org.scalatest.concurrent.ScalaFutures

import org.alephium.flow.handler.FlowHandler.BlockNotify
import org.alephium.json.Json._
import org.alephium.protocol.{BlockHash, Hash}
import org.alephium.protocol.model._
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util._

class WebSocketServerSpec
    extends AlephiumSpec
    with NoIndexModelGenerators
    with ScalatestRouteTest
    with EitherValues
    with ScalaFutures
    with NumericHelpers {
  import ServerFixture._

  behavior of "http"

  it should "encode BlockNotify" in new ServerFixture {
    val dep         = BlockHash.hash("foo")
    val deps        = AVector.fill(groupConfig.depsNum)(dep)
    val header      = BlockHeader.unsafe(deps, Hash.hash("bar"), TimeStamp.zero, Target.Max, 2)
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

  it should "complete on `complete` command" in new AlephiumActorSpec("Websocket") {
    val (actorRef, source) = WebSocketServer.Websocket.actorRef
    val sinkProbe          = source.runWith(TestSink.probe[String])
    val message            = "Hello"
    actorRef ! message
    actorRef ! WebSocketServer.Websocket.Completed
    sinkProbe.request(1).expectNext(message).expectComplete()
  }

  it should "stop on `Failed` command" in new AlephiumActorSpec("Websocket") {
    val (actorRef, source) = WebSocketServer.Websocket.actorRef
    val sinkProbe          = source.runWith(TestSink.probe[String])
    val message            = "Hello"
    actorRef ! message
    actorRef ! WebSocketServer.Websocket.Failed
    sinkProbe.request(1).expectNextOrError() match {
      case Right(hello) => hello is message
      case Left(error)  => error.getMessage is "failure on events websocket"
    }
  }

  trait WebSocketServerFixture extends ServerFixture {

    lazy val blockFlowProbe = TestProbe()
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
    val client = WSProbe()

    val blockNotify = BlockNotify(blockGen.sample.get.header, height = 0)
    def sendEventAndCheck: Assertion = {
      node.eventBus ! blockNotify
      val TextMessage.Strict(message) = client.expectMessage()

      val notification = read[NotificationUnsafe](message).asNotification.toOption.get

      notification.method is "block_notify"
    }

    def checkWS[A](f: => A): A =
      WS("/events", client.flow) ~> server.wsRoute ~> check {
        isWebSocketUpgrade is true
        f
      }
  }
}
