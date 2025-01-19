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
import org.alephium.app.ws.WsSubscriptionHandler.{GetSubscriptions, WsImmutableSubscriptions}
import org.alephium.flow.handler.AllHandlers.{BlockNotify, TxNotify}
import org.alephium.rpc.model.JsonRPC
import org.alephium.rpc.model.JsonRPC.Response
import org.alephium.util._

class WsClientServerSpec extends AlephiumSpec {

  "WsClient" should "fail gracefully when correlationId is reused" in new WsClientServerFixture {
    testWsAndClose(wsClient.connect(wsPort)(_ => ())(_ => ())) { ws =>
      val _ = ws.subscribeToBlock(corId(0))
      ws.subscribeToTx(corId(0))
        .failed
        .futureValue
        .getMessage
        .contains("being already handled") is true
    }
  }

  "WsClient" should "allow for reusing correlationId when request in flight finished" in new WsClientServerFixture {
    testWsAndClose(wsClient.connect(wsPort)(_ => ())(_ => ())) { ws =>
      inside(ws.subscribeToBlock(corId(0)).futureValue) { case JsonRPC.Response.Success(_, id) =>
        id is corId(0)
      }
      inside(ws.subscribeToTx(corId(0)).futureValue) { case JsonRPC.Response.Success(_, id) =>
        id is corId(0)
      }
    }
  }

  "WsServer" should "keep ws connection alive" in new WsClientServerFixture {
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
        corId(0),
        ContractEventsSubscribeParams(duplicateAddresses, Some(EventIndex_0))
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
      val emptyAddressRequest =
        WsRequest(
          corId(0),
          ContractEventsSubscribeParams(AVector.empty, Some(EventIndex_0))
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
        corId(0),
        ContractEventsSubscribeParams(tooManyContractAddresses.tail, Some(EventIndex_0))
      )
      inside(ws.writeRequestToSocket(req).futureValue) { case JsonRPC.Response.Success(_, id) =>
        id is corId(0)
      }
    }
  }

  "WsServer" should "reject subscription which is over limit" in new WsClientServerFixture {
    testWsAndClose(wsClient.connect(wsPort)(_ => ())(_ => ())) { ws =>
      val responses =
        Future
          .sequence(
            AVector
              .tabulate(node.config.network.wsMaxSubscriptionsPerConnection) { index =>
                val req = WsRequest(
                  index.toLong,
                  ContractEventsSubscribeParams(params_addr_01_eventIndex_0.addresses, Some(index))
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
      val requestOverLimit = WsRequest(50L, params_addr_12_eventIndex_1)
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
        corId(0),
        ContractEventsSubscribeParams(tooManyContractAddresses, Some(EventIndex_0))
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
      ws.subscribeToBlock(corId(0)).futureValue is Response.successful(
        corId(0),
        Block.subscriptionId
      )
      ws.unsubscribeFromBlock(corId(1)).futureValue is Response.successful(corId(1))

      // for tx notification
      ws.subscribeToTx(corId(2)).futureValue is Response.successful(corId(2), Tx.subscriptionId)
      ws.unsubscribeFromTx(corId(3)).futureValue is Response.successful(corId(3))

      // for filtered contract events notifications
      val filteredParams =
        ContractEventsSubscribeParams.fromSingle(contractAddress_0, Some(EventIndex_0))
      ws.subscribeToContractEvents(corId(4), filteredParams.addresses, filteredParams.eventIndex)
        .futureValue is Response
        .successful(corId(4), filteredParams.subscriptionId)
      ws.unsubscribeFromContractEvents(corId(5), filteredParams.subscriptionId)
        .futureValue is Response
        .successful(corId(5))

      // for all contract events notifications
      val params = ContractEventsSubscribeParams.fromSingle(contractAddress_0, None)
      ws.subscribeToContractEvents(corId(6), params.addresses, params.eventIndex)
        .futureValue is Response
        .successful(corId(6), params.subscriptionId)
      ws.unsubscribeFromContractEvents(corId(7), params.subscriptionId).futureValue is Response
        .successful(corId(7))
    }
  }

  "WsClient and WsServer" should "unregister and clean all subscriptions on websocket disconnection" in new WsClientServerFixture {
    testWsAndClose(wsClient.connect(wsPort)(_ => ())(_ => ())) { ws =>
      ws.subscribeToBlock(corId(0)).futureValue is Response.successful(
        corId(0),
        Block.subscriptionId
      )
      ws.subscribeToTx(corId(1)).futureValue is Response.successful(corId(1), Tx.subscriptionId)
      val params = ContractEventsSubscribeParams.fromSingle(contractAddress_0, Some(EventIndex_0))
      ws.subscribeToContractEvents(corId(2), params.addresses, params.eventIndex)
        .futureValue is Response
        .successful(corId(2), params.subscriptionId)

      val responseBeforeClose =
        subscriptionHandler.ask(GetSubscriptions).mapTo[WsImmutableSubscriptions].futureValue
      responseBeforeClose.connections.nonEmpty is true
      responseBeforeClose.subscriptionsByContractKey.nonEmpty is true
      responseBeforeClose.contractKeysBySubscription.nonEmpty is true

      ws.close().futureValue

      eventually {
        val responseAfterClose =
          subscriptionHandler
            .ask(GetSubscriptions)
            .mapTo[WsImmutableSubscriptions]
            .futureValue
        responseAfterClose.connections.isEmpty is true
        responseAfterClose.subscriptionsByContractKey.isEmpty is true
        responseAfterClose.contractKeysBySubscription.isEmpty is true
      }
    }
  }

  "WsServer" should "respond already subscribed or unsubscribed" in new WsClientServerFixture {
    testWsAndClose(wsClient.connect(wsPort)(_ => ())(_ => ())) { ws =>
      // for block
      ws.subscribeToBlock(corId(0)).futureValue is Response.successful(
        corId(0),
        Block.subscriptionId
      )
      ws.subscribeToBlock(corId(1)).futureValue is
        Response.failed(corId(1), WsError.alreadySubscribed(Block.subscriptionId))
      ws.unsubscribeFromBlock(corId(2)).futureValue is Response.successful(corId(2))
      ws.unsubscribeFromBlock(corId(3)).futureValue is
        Response.failed(corId(3), WsError.alreadyUnSubscribed(Block.subscriptionId))

      // for tx
      ws.subscribeToTx(corId(4)).futureValue is Response.successful(corId(4), Tx.subscriptionId)
      ws.subscribeToTx(corId(5)).futureValue is
        Response.failed(corId(5), WsError.alreadySubscribed(Tx.subscriptionId))
      ws.unsubscribeFromTx(corId(6)).futureValue is Response.successful(corId(6))
      ws.unsubscribeFromTx(corId(7)).futureValue is
        Response.failed(corId(7), WsError.alreadyUnSubscribed(Tx.subscriptionId))

      // for filtered contract events
      val filteredParams =
        ContractEventsSubscribeParams.fromSingle(contractAddress_0, Some(EventIndex_0))
      ws.subscribeToContractEvents(corId(8), filteredParams.addresses, filteredParams.eventIndex)
        .futureValue is Response
        .successful(corId(8), filteredParams.subscriptionId)
      ws.subscribeToContractEvents(corId(9), filteredParams.addresses, filteredParams.eventIndex)
        .futureValue is
        Response.failed(corId(9), WsError.alreadySubscribed(filteredParams.subscriptionId))
      ws.unsubscribeFromContractEvents(corId(10), filteredParams.subscriptionId)
        .futureValue is Response
        .successful(corId(10))
      ws.unsubscribeFromContractEvents(corId(11), filteredParams.subscriptionId).futureValue is
        Response.failed(corId(11), WsError.alreadyUnSubscribed(filteredParams.subscriptionId))

      // for all contract events
      val params = ContractEventsSubscribeParams.fromSingle(contractAddress_0, None)
      ws.subscribeToContractEvents(corId(12), params.addresses, params.eventIndex)
        .futureValue is Response
        .successful(corId(12), params.subscriptionId)
      ws.subscribeToContractEvents(corId(13), params.addresses, params.eventIndex).futureValue is
        Response.failed(corId(13), WsError.alreadySubscribed(params.subscriptionId))
      ws.unsubscribeFromContractEvents(corId(14), params.subscriptionId).futureValue is Response
        .successful(corId(14))
      ws.unsubscribeFromContractEvents(corId(15), params.subscriptionId).futureValue is
        Response.failed(corId(15), WsError.alreadyUnSubscribed(params.subscriptionId))
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
                ws.subscribeToContractEvents(index + 2, contractAddresses, Some(EventIndex_0))
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
        ContractEventsSubscribeParams
          .fromSingle(contractAddress_0, Some(EventIndex_0))
          .subscriptionId

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
