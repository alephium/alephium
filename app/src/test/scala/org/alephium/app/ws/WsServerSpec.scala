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

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import akka.testkit.TestProbe
import org.scalatest.EitherValues
import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.exceptions.TestFailedException

import org.alephium.app.ws.WsParams.SubscribeParams.Block
import org.alephium.app.ws.WsRequest.Correlation
import org.alephium.flow.handler.AllHandlers.BlockNotify
import org.alephium.json.Json._
import org.alephium.rpc.model.JsonRPC.{Error, Response}
import org.alephium.util._

class WsServerSpec extends AlephiumFutureSpec with EitherValues with NumericHelpers {
  import WsUtils._
  behavior of "WebSocketServer"

  it should "reject request to any endpoint besides /ws" in new WsServerFixture {
    val wsServer = bindAndListen()
    assertThrows[TestFailedException](
      wsClient.connect(wsPort, uri = "/wrong").futureValue
    )
    wsClient.connect(wsPort).futureValue.isClosed is false
    wsServer.httpServer.close().asScala.futureValue
  }

  it should "initialize subscription and event handler" in new WsServerFixture with Eventually {
    val WsServer(httpServer, eventHandler, subscriptionHandler) = bindAndListen()
    eventually(testSubscriptionHandlerInitialized(subscriptionHandler))
    eventually(testEventHandlerInitialized(eventHandler))
    httpServer.close().asScala.futureValue
  }

  it should "acknowledge subscriptions and unsubscriptions" in new WsServerFixture {
    val wsServer    = bindAndListen()
    val ws          = wsClient.connect(wsPort).futureValue
    val clientProbe = TestProbe()
    ws.textMessageHandler(message => clientProbe.ref ! message)

    ws.subscribeToBlock(0).futureValue
    clientProbe.expectMsgType[String] is write(
      Response.successful(Correlation(0), Block.subscriptionId)
    )

    ws.unsubscribeFromBlock(1).futureValue
    clientProbe.expectMsgType[String] is write(Response.successful(Correlation(1)))

    ws.close().futureValue
    clientProbe.expectNoMessage()
    wsServer.httpServer.close().asScala.futureValue
  }

  it should "respond already subscribed or unsubscribed" in new WsServerFixture {
    val wsServer    = bindAndListen()
    val ws          = wsClient.connect(wsPort).futureValue
    val clientProbe = TestProbe()
    ws.textMessageHandler(message => clientProbe.ref ! message)

    ws.subscribeToBlock(0).futureValue
    clientProbe.expectMsgType[String] is write(
      Response.successful(Correlation(0), Block.subscriptionId)
    )

    ws.subscribeToBlock(1).futureValue
    clientProbe.expectMsgType[String] is write(
      Response.failed(Correlation(1), Error(WsError.AlreadySubscribed, Block.subscriptionId))
    )

    ws.unsubscribeFromBlock(2).futureValue
    clientProbe.expectMsgType[String] is write(Response.successful(Correlation(2)))

    ws.unsubscribeFromBlock(3).futureValue
    clientProbe.expectMsgType[String] is write(
      Response.failed(Correlation(3), Error(WsError.AlreadyUnsubscribed, Block.subscriptionId))
    )

    ws.close().futureValue
    clientProbe.expectNoMessage()
    wsServer.httpServer.close().asScala.futureValue
  }

  it should "handle high load fast" in new WsServerFixture with IntegrationPatience {
    val numberOfConnections           = maxClientConnections
    override def maxServerConnections = numberOfConnections
    val clientProbe                   = TestProbe()
    val wsServer                      = bindAndListen()

    // let's measure sequential connection, subscription, notification and unsubscription time on local env
    val websockets =
      Future
        .sequence(
          AVector
            .tabulate(numberOfConnections) { index =>
              for {
                ws <- wsClient.connect(wsPort)
                _ = ws.textMessageHandler(message => clientProbe.ref ! message)
              } yield index.toLong -> ws
            }
            .toSeq
        )
        .futureValue // 500 connections in 800 millis

    // 500 subscription requests in 25 millis
    Future
      .sequence(websockets.map { case (index, ws) => ws.subscribeToBlock(index) })
      .futureValue
    // 500 subscription responses in 25 millis
    clientProbe.receiveN(numberOfConnections, 3.seconds)
    node.eventBus ! BlockNotify(dummyBlock, 0)
    // 500 notifications in 400 millis (Blocks are big, IO + serialization/deserialization time)
    clientProbe.receiveN(numberOfConnections, 10.seconds)
    // 500 unsubscription requests in 25 millis
    Future
      .sequence(websockets.map { case (index, ws) => ws.unsubscribeFromBlock(index) })
      .futureValue
    // 500 unsubscription responses in 25 millis
    clientProbe.receiveN(numberOfConnections, 3.seconds)
    node.eventBus ! BlockNotify(dummyBlock, 1)
    clientProbe.expectNoMessage()
    wsServer.httpServer.close().asScala.futureValue
    // current measure times :
    // 500 takes 1.2 seconds
    // 1000 takes 1.6 seconds
    // 2000 takes 2.0 seconds
    // 5000 takes 2.7 seconds
    // 10000 takes 4.2 seconds
  }

}
