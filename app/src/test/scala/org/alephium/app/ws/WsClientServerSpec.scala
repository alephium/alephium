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
import org.scalatest.Inside.inside
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.exceptions.TestFailedException

import org.alephium.app.ws.WsParams.ContractEventsSubscribeParams
import org.alephium.app.ws.WsParams.SimpleSubscribeParams.{Block, Tx}
import org.alephium.app.ws.WsRequest.Correlation
import org.alephium.app.ws.WsSubscriptionHandler.{GetSubscriptions, WsImmutableSubscriptions}
import org.alephium.flow.handler.AllHandlers.{BlockNotify, TxNotify}
import org.alephium.rpc.model.JsonRPC
import org.alephium.rpc.model.JsonRPC.Response
import org.alephium.util._

class WsClientServerSpec extends AlephiumSpec with WsSubscriptionFixture {

  "WsServer" should "keep websocket connection alive" in new WsClientServerFixture {
    override val keepAliveInterval = Duration.ofMillisUnsafe(20)
    val keepAliveProbe             = TestProbe()
    testWsAndClose(wsClient.connect(wsPort)(_ => ())(keepAliveProbe.ref ! _)) { _ =>
      eventually {
        keepAliveProbe.expectMsgType[WsClient.KeepAlive]
      }
    }
  }

  "WsServer" should "reject request to any endpoint besides /ws" in new WsClientServerFixture {
    assertThrows[TestFailedException](
      wsClient.connect(wsPort, uri = "/wrong")(_ => ())(_ => ()).futureValue
    )
    testWsAndClose(wsClient.connect(wsPort)(_ => ())(_ => ())) { ws =>
      ws.isClosed is false
    }
  }

  "WsServer" should "initialize subscription and event handler" in new WsClientServerFixture {
    eventually(testSubscriptionHandlerInitialized(subscriptionHandler))
    eventually(testEventHandlerInitialized(eventHandler))
  }

  "WsServer" should "reject invalid contract events subscription requests with duplicate addresses" in new WsClientServerFixture {
    testWsAndClose(wsClient.connect(wsPort)(_ => ())(_ => ())) { ws =>
      val duplicateAddressRequest = WsRequest(
        Correlation(0),
        ContractEventsSubscribeParams(
          ContractEventsSubscribeParams.ContractEvent,
          0,
          duplicateAddresses
        )
      )
      ws.writeRequestToSocket(duplicateAddressRequest).futureValue is Response
        .failed(
          duplicateAddressRequest.id,
          WsError.duplicatedAddresses(contractAddress_0.toBase58)
        )
    }
  }

  "WsServer" should "reject invalid contract events subscription requests with empty addresses" in new WsClientServerFixture {
    testWsAndClose(wsClient.connect(wsPort)(_ => ())(_ => ())) { ws =>
      val emptyAddressRequest = WsRequest(
        Correlation(0),
        ContractEventsSubscribeParams(ContractEventsSubscribeParams.ContractEvent, 0, AVector.empty)
      )
      ws.writeRequestToSocket(emptyAddressRequest).futureValue is Response
        .failed(
          emptyAddressRequest.id,
          WsError.emptyContractAddress
        )
    }
  }

  "WsServer" should "handle ws connection with maximum contract event addresses within wsMaxFrameSize" in new WsClientServerFixture {
    testWsAndClose(wsClient.connect(wsPort)(_ => ())(_ => ())) { ws =>
      val req = WsRequest(
        Correlation(0L),
        ContractEventsSubscribeParams(
          ContractEventsSubscribeParams.ContractEvent,
          0,
          tooManyContractAddresses.tail
        )
      )
      inside(ws.writeRequestToSocket(req).futureValue) { case JsonRPC.Response.Success(_, id) =>
        id is 0L
      }
    }
  }

  "WsServer" should "reject subscriptions over limit" in new WsClientServerFixture {
    testWsAndClose(wsClient.connect(wsPort)(_ => ())(_ => ())) { ws =>
      val responses =
        Future
          .sequence(
            AVector
              .tabulate(50) { index =>
                val req = WsRequest(
                  Correlation(index.toLong),
                  ContractEventsSubscribeParams(
                    ContractEventsSubscribeParams.ContractEvent,
                    index,
                    contractEventsParams_0.addresses
                  )
                )
                ws.writeRequestToSocket(req)
              }
              .toIterable
          )
          .futureValue(Timeout(1.second))

      responses.foreach {
        case JsonRPC.Response.Success(_, _) =>
        case JsonRPC.Response.Failure(error, _) =>
          fail(error.getMessage)
      }
      val requestOverLimit = WsRequest(
        Correlation(50L),
        ContractEventsSubscribeParams(
          ContractEventsSubscribeParams.ContractEvent,
          50,
          contractEventsParams_1.addresses
        )
      )
      ws.writeRequestToSocket(requestOverLimit).futureValue is Response
        .failed(
          requestOverLimit.id,
          WsError.subscriptionLimitExceeded(node.config.network.wsMaxSubscriptionsPerConnection)
        )
    }
  }

  "WsServer" should "reject invalid contract events subscription requests with too many addresses" in new WsClientServerFixture {
    testWsAndClose(wsClient.connect(wsPort)(_ => ())(_ => ())) { ws =>
      val tooManyAddressesRequest = WsRequest(
        Correlation(0),
        ContractEventsSubscribeParams(
          ContractEventsSubscribeParams.ContractEvent,
          0,
          tooManyContractAddresses
        )
      )
      ws.writeRequestToSocket(tooManyAddressesRequest).futureValue is Response
        .failed(
          tooManyAddressesRequest.id,
          WsError.tooManyContractAddresses(node.config.network.wsMaxContractEventAddresses)
        )
    }
  }

  "WsClient and WsServer" should "subscribe/unsubscribe and acknowledge by response" in new WsClientServerFixture {
    testWsAndClose(wsClient.connect(wsPort)(_ => ())(_ => ())) { ws =>
      // for block notification
      ws.subscribeToBlock(0).futureValue is Response.successful(
        Correlation(0),
        Block.subscriptionId
      )
      ws.unsubscribeFromBlock(1).futureValue is Response.successful(Correlation(1))

      // for tx notification
      ws.subscribeToTx(2).futureValue is Response.successful(Correlation(2), Tx.subscriptionId)
      ws.unsubscribeFromTx(3).futureValue is Response.successful(Correlation(3))

      // for contract events notifications
      val params = ContractEventsSubscribeParams.fromSingle(EventIndex_0, contractAddress_0)
      ws.subscribeToContractEvents(4, params.eventIndex, params.addresses).futureValue is Response
        .successful(Correlation(4), params.subscriptionId)
      ws.unsubscribeFromContractEvents(5, params.subscriptionId).futureValue is Response.successful(
        Correlation(5)
      )
    }
  }

  "WsClient and WsServer" should "unregister and clean all subscriptions on websocket disconnection" in new WsClientServerFixture {
    testWsAndClose(wsClient.connect(wsPort)(_ => ())(_ => ())) { ws =>
      ws.subscribeToBlock(0).futureValue is Response.successful(
        Correlation(0),
        Block.subscriptionId
      )
      ws.subscribeToTx(2).futureValue is Response.successful(Correlation(2), Tx.subscriptionId)
      val params = ContractEventsSubscribeParams.fromSingle(EventIndex_0, contractAddress_0)
      ws.subscribeToContractEvents(4, params.eventIndex, params.addresses).futureValue is Response
        .successful(Correlation(4), params.subscriptionId)

      val responseBeforeClose =
        subscriptionHandler.ask(GetSubscriptions).mapTo[WsImmutableSubscriptions].futureValue
      responseBeforeClose.subscriptions.nonEmpty is true
      responseBeforeClose.subscriptionsByAddress.nonEmpty is true
      responseBeforeClose.addressesBySubscriptionId.nonEmpty is true

      ws.close().futureValue

      eventually {
        val responseAfterClose =
          subscriptionHandler
            .ask(GetSubscriptions)
            .mapTo[WsImmutableSubscriptions]
            .futureValue
        responseAfterClose.subscriptions.isEmpty is true
        responseAfterClose.subscriptionsByAddress.isEmpty is true
        responseAfterClose.addressesBySubscriptionId.isEmpty is true
      }
    }
  }

  "WsServer" should "respond already subscribed or unsubscribed" in new WsClientServerFixture {
    testWsAndClose(wsClient.connect(wsPort)(_ => ())(_ => ())) { ws =>
      // for block
      ws.subscribeToBlock(0).futureValue is Response.successful(
        Correlation(0),
        Block.subscriptionId
      )
      ws.subscribeToBlock(1).futureValue is
        Response.failed(Correlation(1), WsError.alreadySubscribed(Block.subscriptionId))
      ws.unsubscribeFromBlock(2).futureValue is Response.successful(Correlation(2))
      ws.unsubscribeFromBlock(3).futureValue is
        Response.failed(Correlation(3), WsError.alreadyUnSubscribed(Block.subscriptionId))

      // for tx
      ws.subscribeToTx(4).futureValue is Response.successful(Correlation(4), Tx.subscriptionId)
      ws.subscribeToTx(5).futureValue is
        Response.failed(Correlation(5), WsError.alreadySubscribed(Tx.subscriptionId))
      ws.unsubscribeFromTx(6).futureValue is Response.successful(Correlation(6))
      ws.unsubscribeFromTx(7).futureValue is
        Response.failed(Correlation(7), WsError.alreadyUnSubscribed(Tx.subscriptionId))

      // for contract events
      val params = ContractEventsSubscribeParams.fromSingle(EventIndex_0, contractAddress_0)
      ws.subscribeToContractEvents(8, params.eventIndex, params.addresses).futureValue is Response
        .successful(Correlation(8), params.subscriptionId)
      ws.subscribeToContractEvents(9, params.eventIndex, params.addresses).futureValue is
        Response.failed(Correlation(9), WsError.alreadySubscribed(params.subscriptionId))
      ws.unsubscribeFromContractEvents(10, params.subscriptionId).futureValue is Response
        .successful(
          Correlation(10)
        )
      ws.unsubscribeFromContractEvents(11, params.subscriptionId).futureValue is
        Response.failed(Correlation(11), WsError.alreadyUnSubscribed(params.subscriptionId))
    }
  }

  "WsClient and WsServer" should "handle high load of block, tx and event contract subscriptions, notifications and unsubscriptions" in new WsClientServerFixture {
    val numberOfConnections           = maxClientConnections
    override def maxServerConnections = numberOfConnections
    val clientProbe                   = TestProbe()

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
                  .connect(wsPort)(clientProbe.ref ! _)(_ => ())
                  .map(index.toLong -> _)
              }
              .toSeq
          )
          .futureValue
      }
    val contractAddresses = AVector(contractAddress_0)
    try {
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
        logStatesFor(AVector(contractAddress_0.contractId -> EventIndex_0))
      )

      measureTime(s"$numberOfNotifications notifications with ser/deser") {
        clientProbe.receiveN(numberOfNotifications, 10.seconds)
      }

      val contractEventSubscriptionId =
        ContractEventsSubscribeParams.fromSingle(EventIndex_0, contractAddress_0).subscriptionId

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
      clientProbe.expectNoMessage(50.millis)
    } finally {
      Future.sequence(websockets.map(_._2.close())).futureValue
      ()
    }
  }
}
