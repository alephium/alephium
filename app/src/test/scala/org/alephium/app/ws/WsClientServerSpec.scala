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

class WsClientServerSpec extends AlephiumFutureSpec with EitherValues with NumericHelpers {
  import WsUtils._

  "WsServer" should "reject request to any endpoint besides /ws" in new WsServerFixture {
    val wsServer = bindAndListen()
    assertThrows[TestFailedException](
      wsClient.connect(wsPort, uri = "/wrong")(_ => ()).futureValue
    )
    wsClient.connect(wsPort)(_ => ()).futureValue.isClosed is false
    wsServer.httpServer.close().asScala.futureValue
  }

  "WsServer" should "initialize subscription and event handler" in new WsServerFixture
    with Eventually {
    val WsServer(httpServer, eventHandler, subscriptionHandler) = bindAndListen()
    eventually(testSubscriptionHandlerInitialized(subscriptionHandler))
    eventually(testEventHandlerInitialized(eventHandler))
    httpServer.close().asScala.futureValue
  }

  "WsClient and WsServer" should "subscribe/unsubscribe and acknowledge by response" in new WsServerFixture {
    val wsServer = bindAndListen()
    val ws       = wsClient.connect(wsPort)(_ => ()).futureValue

    ws.subscribeToBlock(0).futureValue is Response.successful(Correlation(0), Block.subscriptionId)
    ws.unsubscribeFromBlock(1).futureValue is Response.successful(Correlation(1))
    ws.close().futureValue
    wsServer.httpServer.close().asScala.futureValue
  }

  "WsServer" should "respond already subscribed or unsubscribed" in new WsServerFixture {
    val wsServer = bindAndListen()
    val ws       = wsClient.connect(wsPort)(_ => ()).futureValue

    ws.subscribeToBlock(0).futureValue is Response.successful(Correlation(0), Block.subscriptionId)
    ws.subscribeToBlock(1).futureValue is
      Response.failed(Correlation(1), Error(WsError.AlreadySubscribed, Block.subscriptionId))

    ws.unsubscribeFromBlock(2).futureValue is Response.successful(Correlation(2))
    ws.unsubscribeFromBlock(3).futureValue is
      Response.failed(Correlation(3), Error(WsError.AlreadyUnsubscribed, Block.subscriptionId))

    ws.close().futureValue
    wsServer.httpServer.close().asScala.futureValue
  }

  "WsClient and WsServer" should "handle high load fast" in new WsServerFixture
    with IntegrationPatience {
    val numberOfConnections           = maxClientConnections
    override def maxServerConnections = numberOfConnections
    val clientProbe                   = TestProbe()
    val wsServer                      = bindAndListen()

    val websockets =
      measureTime(s"$numberOfConnections connections") {
        Future
          .sequence(
            AVector
              .tabulate(numberOfConnections) { index =>
                wsClient
                  .connect(wsPort)(clientProbe.ref ! _)
                  .map(index.toLong -> _)
              }
              .toSeq
          )
          .futureValue
      }

    measureTime(s"$numberOfConnections subscription requests/responses with ser/deser") {
      Future
        .sequence(websockets.map { case (index, ws) => ws.subscribeToBlock(index) })
        .futureValue
        .collectFirst { case _: Response.Failure =>
          fail("Failed to subscribe")
        }
    }
    node.eventBus ! BlockNotify(dummyBlock, 0)
    measureTime(s"$numberOfConnections notifications with ser/deser") {
      clientProbe.receiveN(numberOfConnections, 10.seconds)
    }
    measureTime(s"$numberOfConnections unsubscriptions requests/responses with ser/deser") {
      Future
        .sequence(websockets.map { case (index, ws) => ws.unsubscribeFromBlock(index) })
        .futureValue
        .collectFirst { case _: Response.Failure =>
          fail("Failed to unsubscribe")
        }
    }
    node.eventBus ! BlockNotify(dummyBlock, 1)
    clientProbe.expectNoMessage()
    wsServer.httpServer.close().asScala.futureValue
  }
}
