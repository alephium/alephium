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
import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.exceptions.TestFailedException

import org.alephium.app.ws.WsParams.ContractEventsSubscribeParams
import org.alephium.app.ws.WsParams.SimpleSubscribeParams.{Block, Tx}
import org.alephium.app.ws.WsRequest.Correlation
import org.alephium.flow.handler.AllHandlers.{BlockNotify, TxNotify}
import org.alephium.json.Json._
import org.alephium.rpc.model.JsonRPC.{Error, Response}
import org.alephium.util._

class WsClientServerSpec extends WsSpec {
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

    // for block notification
    ws.subscribeToBlock(0).futureValue is Response.successful(Correlation(0), Block.subscriptionId)
    ws.unsubscribeFromBlock(1).futureValue is Response.successful(Correlation(1))

    // for tx notification
    ws.subscribeToTx(2).futureValue is Response.successful(Correlation(2), Tx.subscriptionId)
    ws.unsubscribeFromTx(3).futureValue is Response.successful(Correlation(3))

    // for contract events notifications
    val params = ContractEventsSubscribeParams.from(EventIndex_0, AVector(contractAddress_0))
    ws.subscribeToContractEvents(4, params.eventIndex, params.addresses).futureValue is Response
      .successful(
        Correlation(4),
        params.subscriptionId
      )
    ws.unsubscribeFromContractEvents(5, params.subscriptionId).futureValue is Response.successful(
      Correlation(5)
    )
    ws.close().futureValue
    wsServer.httpServer.close().asScala.futureValue
  }

  "WsServer" should "respond already subscribed or unsubscribed" in new WsServerFixture {
    val wsServer = bindAndListen()
    val ws       = wsClient.connect(wsPort)(_ => ()).futureValue

    // for block
    ws.subscribeToBlock(0).futureValue is Response.successful(Correlation(0), Block.subscriptionId)
    ws.subscribeToBlock(1).futureValue is
      Response.failed(Correlation(1), Error(WsError.AlreadySubscribed, Block.subscriptionId))
    ws.unsubscribeFromBlock(2).futureValue is Response.successful(Correlation(2))
    ws.unsubscribeFromBlock(3).futureValue is
      Response.failed(Correlation(3), Error(WsError.AlreadyUnsubscribed, Block.subscriptionId))

    // for tx
    ws.subscribeToTx(4).futureValue is Response.successful(Correlation(4), Tx.subscriptionId)
    ws.subscribeToTx(5).futureValue is
      Response.failed(Correlation(5), Error(WsError.AlreadySubscribed, Tx.subscriptionId))
    ws.unsubscribeFromTx(6).futureValue is Response.successful(Correlation(6))
    ws.unsubscribeFromTx(7).futureValue is
      Response.failed(Correlation(7), Error(WsError.AlreadyUnsubscribed, Tx.subscriptionId))

    // for contract events
    val params = ContractEventsSubscribeParams.from(EventIndex_0, AVector(contractAddress_0))
    ws.subscribeToContractEvents(8, params.eventIndex, params.addresses).futureValue is Response
      .successful(Correlation(8), params.subscriptionId)
    ws.subscribeToContractEvents(9, params.eventIndex, params.addresses).futureValue is
      Response.failed(Correlation(9), Error(WsError.AlreadySubscribed, params.subscriptionId))
    ws.unsubscribeFromContractEvents(10, params.subscriptionId).futureValue is Response.successful(
      Correlation(10)
    )
    ws.unsubscribeFromContractEvents(11, params.subscriptionId).futureValue is
      Response.failed(Correlation(11), Error(WsError.AlreadyUnsubscribed, params.subscriptionId))

    ws.close().futureValue
    wsServer.httpServer.close().asScala.futureValue
  }

  "WsClient and WsServer" should "handle high load of block, tx and event contract subscriptions, notifications and unsubscriptions" in new WsServerFixture
    with IntegrationPatience {
    val numberOfConnections           = maxClientConnections
    override def maxServerConnections = numberOfConnections
    val clientProbe                   = TestProbe()
    val wsServer                      = bindAndListen()

    val numberOfSubscriptions   = numberOfConnections * 3
    val numberOfNotifications   = numberOfConnections * 3
    val numberOfUnSubscriptions = numberOfConnections * 3

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

    val contractAddresses = AVector(contractAddress_0)

    measureTime(s"$numberOfSubscriptions subscription requests/responses with ser/deser") {
      Future
        .sequence(
          websockets.flatMap { case (index, ws) =>
            AVector(
              ws.subscribeToBlock(index),
              ws.subscribeToTx(index + 1),
              ws.subscribeToContractEvents(index + 2, EventIndex_0, contractAddresses)
            )
          }
        )
        .futureValue
        .collectFirst { case _: Response.Failure =>
          fail("Failed to subscribe")
        }
    }
    node.eventBus ! TxNotify(transactionGen().sample.get.toTemplate, TimeStamp.now())
    node.eventBus ! BlockNotify(
      dummyBlock,
      0,
      logStatesFor(AVector(contractAddress_0.contractId -> EventIndex_0), transactionGen())
    )

    measureTime(s"$numberOfNotifications notifications with ser/deser") {
      clientProbe.receiveN(numberOfNotifications, 10.seconds)
    }

    val contractEventSubscriptionId =
      ContractEventsSubscribeParams.from(EventIndex_0, contractAddresses).subscriptionId

    measureTime(s"$numberOfUnSubscriptions unsubscription requests/responses with ser/deser") {
      Future
        .sequence(websockets.flatMap { case (index, ws) =>
          AVector(
            ws.unsubscribeFromBlock(index + 3),
            ws.unsubscribeFromTx(index + 4),
            ws.unsubscribeFromContractEvents(index + 5, contractEventSubscriptionId)
          )

        })
        .futureValue
        .collectFirst { case _: Response.Failure =>
          fail("Failed to unsubscribe")
        }
    }

    node.eventBus ! BlockNotify(dummyBlock, 1, AVector.empty)
    clientProbe.expectNoMessage()
    wsServer.httpServer.close().asScala.futureValue
  }
}
