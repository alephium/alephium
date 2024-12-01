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

import scala.concurrent.ExecutionContext.Implicits
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

import akka.testkit.TestProbe
import io.vertx.core.{Future => VertxFuture}
import org.scalatest.concurrent.IntegrationPatience

import org.alephium.app.ServerFixture
import org.alephium.app.ws.WsParams.SubscribeParams
import org.alephium.flow.handler.AllHandlers.BlockNotify
import org.alephium.json.Json._
import org.alephium.util._

class WsSubscriptionHandlerSpec extends AlephiumSpec with ServerFixture with WsUtils {

  "WsUtils" should "convert VertxFuture to Scala Future" in {
    val successfulVertxFuture: VertxFuture[String] = VertxFuture.succeededFuture("Success Result")
    val successfulScalaFuture: Future[String]      = successfulVertxFuture.asScala
    successfulScalaFuture.onComplete {
      case Success(value) =>
        value is "Success Result"
      case Failure(_) =>
        fail("The future should not fail in this test case")
    }(Implicits.global)

    val exception                              = new RuntimeException("Test Failure")
    val failedVertxFuture: VertxFuture[String] = VertxFuture.failedFuture(exception)
    val failedScalaFuture: Future[String]      = failedVertxFuture.asScala

    failedScalaFuture.onComplete {
      case Success(_) =>
        fail("The future should not succeed in this test case")
      case Failure(ex) =>
        ex is exception // Expected exception is thrown
    }(Implicits.global)
  }

  "WsSubscriptionHandler" should "handle high load fast" in new WebSocketServerFixture
    with IntegrationPatience {
    val numberOfConnections     = 500
    override def maxConnections = numberOfConnections
    val clientProbe             = TestProbe()
    httpServer.webSocketHandler { webSocket =>
      subscriptionHandler ! WsSubscriptionHandler.ConnectAndSubscribe(webSocket)
    }
    val httpBinding    = bindAndListen()
    val subscribeReq   = WsRequest.subscribe(0, SubscribeParams.Block)
    val unsubscribeReq = WsRequest.unsubscribe(1, SubscribeParams.Block.subscriptionId)

    // let's measure sequential connection, subscription, notification and unsubscription time on local env
    val websockets =
      Future
        .sequence(
          AVector
            .fill(numberOfConnections) {
              for {
                ws <- connectWebsocketClient
                _ = ws.textMessageHandler(message => clientProbe.ref ! message)
              } yield ws
            }
            .toSeq
        )
        .futureValue // 500 connections in 800 millis

    // 500 subscription requests in 25 millis
    Future.sequence(websockets.map(_.writeTextMessage(write(subscribeReq)).asScala)).futureValue
    // 500 subscription responses in 25 millis
    clientProbe.receiveN(numberOfConnections, 3.seconds)
    node.eventBus ! BlockNotify(dummyBlock, 0)
    // 500 notifications in 400 millis (Blocks are big, IO + serialization/deserialization time)
    clientProbe.receiveN(numberOfConnections, 10.seconds)
    // 500 unsubscription requests in 25 millis
    Future.sequence(websockets.map(_.writeTextMessage(write(unsubscribeReq)).asScala)).futureValue
    // 500 unsubscription responses in 25 millis
    clientProbe.receiveN(numberOfConnections, 3.seconds)
    node.eventBus ! BlockNotify(dummyBlock, 1)
    clientProbe.expectNoMessage()
    httpBinding.close().asScala.futureValue
    // current measure times :
    // 500 takes 1.4 seconds
    // 1000 takes 1.8 seconds
    // 2000 takes 2.2 seconds
    // 5000 takes 3 seconds
    // 10000 takes 5 seconds
  }
}
