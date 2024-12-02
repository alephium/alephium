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
      connectWebsocketClient(uri = "/wrong").futureValue
    )
    connectWebsocketClient().futureValue.isClosed is false
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
    val ws          = connectWebsocketClient().futureValue
    val clientProbe = TestProbe()
    ws.textMessageHandler(message => clientProbe.ref ! message)

    val subscribeReq            = WsRequest.subscribe(0, Block)
    val subscribeResp: Response = Response.successful(Correlation(0), Block.subscriptionId)

    ws.writeTextMessage(write(subscribeReq)).asScala.futureValue
    clientProbe.expectMsgType[String] is write(subscribeResp)

    val unsubscribeReq            = WsRequest.unsubscribe(1, Block.subscriptionId)
    val unsubscribeResp: Response = Response.successful(Correlation(1))

    ws.writeTextMessage(write(unsubscribeReq)).asScala.futureValue
    clientProbe.expectMsgType[String] is write(unsubscribeResp)

    ws.close().asScala.futureValue
    clientProbe.expectNoMessage()
    wsServer.httpServer.close().asScala.futureValue
  }

  it should "respond already subscribed or unsubscribed" in new WsServerFixture {
    val wsServer    = bindAndListen()
    val ws          = connectWebsocketClient().futureValue
    val clientProbe = TestProbe()
    ws.textMessageHandler(message => clientProbe.ref ! message)

    val subscribeReq_1                 = WsRequest.subscribe(0, Block)
    val subscribeSuccessResp: Response = Response.successful(Correlation(0), Block.subscriptionId)

    ws.writeTextMessage(write(subscribeReq_1)).asScala.futureValue
    clientProbe.expectMsgType[String] is write(subscribeSuccessResp)

    val subscribeReq_2 = WsRequest.subscribe(1, Block)
    val alreadySubscribedResp: Response =
      Response.failed(Correlation(1), Error(WsError.AlreadySubscribed, Block.subscriptionId))

    ws.writeTextMessage(write(subscribeReq_2)).asScala.futureValue
    clientProbe.expectMsgType[String] is write(alreadySubscribedResp)

    val unsubscribeReq_1                 = WsRequest.unsubscribe(2, Block.subscriptionId)
    val unsubscribeSuccessResp: Response = Response.successful(Correlation(2))

    ws.writeTextMessage(write(unsubscribeReq_1)).asScala.futureValue
    clientProbe.expectMsgType[String] is write(unsubscribeSuccessResp)

    val unsubscribeReq_2 = WsRequest.unsubscribe(3, Block.subscriptionId)
    val alreadyUnsubscribedResp: Response =
      Response.failed(Correlation(3), Error(WsError.AlreadyUnsubscribed, Block.subscriptionId))

    ws.writeTextMessage(write(unsubscribeReq_2)).asScala.futureValue
    clientProbe.expectMsgType[String] is write(alreadyUnsubscribedResp)

    ws.close().asScala.futureValue
    clientProbe.expectNoMessage()
    wsServer.httpServer.close().asScala.futureValue
  }

  it should "handle high load fast" in new WsServerFixture with IntegrationPatience {
    val numberOfConnections     = 500
    override def maxConnections = numberOfConnections
    val clientProbe             = TestProbe()
    val wsServer                = bindAndListen()
    val subscribeReq            = WsRequest.subscribe(0, Block)
    val unsubscribeReq          = WsRequest.unsubscribe(1, Block.subscriptionId)

    // let's measure sequential connection, subscription, notification and unsubscription time on local env
    val websockets =
      Future
        .sequence(
          AVector
            .fill(numberOfConnections) {
              for {
                ws <- connectWebsocketClient()
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
    wsServer.httpServer.close().asScala.futureValue
    // current measure times :
    // 500 takes 1.4 seconds
    // 1000 takes 1.8 seconds
    // 2000 takes 2.2 seconds
    // 5000 takes 3 seconds
    // 10000 takes 5 seconds
  }

}
