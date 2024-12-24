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

import akka.testkit.TestProbe
import org.scalatest.Assertion
import org.scalatest.Inside.inside
import org.scalatest.concurrent.Eventually

import org.alephium.app.ServerFixture
import org.alephium.app.ws.WsParams.{
  ContractEventsSubscribeParams,
  SimpleSubscribeParams,
  UnsubscribeParams,
  WsNotificationParams
}
import org.alephium.app.ws.WsRequest.Correlation
import org.alephium.app.ws.WsSubscriptionHandler._
import org.alephium.app.ws.WsUtils._
import org.alephium.flow.handler.AllHandlers.{BlockNotify, TxNotify}
import org.alephium.json.Json._
import org.alephium.protocol.model.ContractId
import org.alephium.protocol.vm.{LogState, Val}
import org.alephium.rpc.model.JsonRPC
import org.alephium.rpc.model.JsonRPC.{Error, Notification, Response}
import org.alephium.util._

class WsSubscriptionHandlerSpec extends WsSpec with ServerFixture {
  import org.alephium.app.ws.WsBehaviorFixture._

  def logStatesFor(
      contractIdsWithEventIndex: AVector[(ContractId, Int)]
  ): AVector[(ContractId, LogState)] = {
    contractIdsWithEventIndex.map { case (contractId, eventIndex) =>
      contractId -> LogState(
        transactionGen().sample.get.id,
        eventIndex.toByte,
        AVector(Val.U256(1))
      )
    }
  }

  it should "connect and subscribe multiple ws clients to multiple events" in new WsBehaviorFixture {
    def subscribingRequestResponseBehavior(clientProbe: TestProbe): Future[ClientWs] = {
      val contractEventsParams =
        ContractEventsSubscribeParams.from(EventIndex_0, AVector(contractAddress_0))
      for {
        ws                        <- wsClient.connect(wsPort)(ntf => clientProbe.ref ! ntf)
        blockSubscriptionResponse <- ws.subscribeToBlock(0)
        txSubscriptionResponse    <- ws.subscribeToTx(1)
        contractEventsSubscriptionResponse <- ws.subscribeToContractEvents(
          2,
          contractEventsParams.eventIndex,
          contractEventsParams.addresses
        )
      } yield {
        inside(blockSubscriptionResponse) { case JsonRPC.Response.Success(result, id) =>
          result is ujson.Str(SimpleSubscribeParams.Block.subscriptionId)
          id is 0
        }
        inside(txSubscriptionResponse) { case JsonRPC.Response.Success(result, id) =>
          result is ujson.Str(SimpleSubscribeParams.Tx.subscriptionId)
          id is 1
        }
        inside(contractEventsSubscriptionResponse) { case JsonRPC.Response.Success(result, id) =>
          result is ujson.Str(contractEventsParams.subscriptionId)
          id is 2
        }
        ws
      }
    }

    def serverBehavior(eventBusRef: ActorRefT[EventBus.Message]): Unit = {
      eventBusRef ! BlockNotify(
        blockGen.sample.get,
        height = 0,
        logStatesFor(AVector(contractAddress_0.contractId -> EventIndex_0))
      )
      eventBusRef ! TxNotify(transactionGen().sample.get.toTemplate, TimeStamp.now())
    }

    def assertValidNotification(clientProbe: TestProbe): Unit = {
      AVector.fill(3)(clientProbe.expectMsgType[Notification]).foreach { notification =>
        notification.method is WsMethod.SubscriptionMethod
        notification.params.obj.get("result").nonEmpty is true
        notification.params.obj.get("subscription").nonEmpty is true
      }
    }

    val wsInitBehavior =
      WsStartBehavior(subscribingRequestResponseBehavior, serverBehavior, assertValidNotification)
    checkWS(
      initBehaviors = AVector.fill(3)(wsInitBehavior),
      nextBehaviors = AVector.empty,
      expectedSubscriptions = 3 * 3, // 3 clients, 3 subscriptions each
      openWebsocketsCount = 3
    )
  }

  it should "not spin ws connections over limit" in new WsBehaviorFixture {
    override def maxServerConnections: Int = 2
    val wsSpec = WsStartBehavior(
      _ => wsClient.connect(wsPort)(_ => ()),
      _ => (),
      _ => true is true
    )
    checkWS(
      initBehaviors = AVector.fill(3)(wsSpec),
      nextBehaviors = AVector.empty,
      expectedSubscriptions = 0,
      openWebsocketsCount = 3
    )
  }

  it should "handle invalid messages with error" in new WsBehaviorFixture {
    def invalidMessageBehavior(clientProbe: TestProbe): Future[ClientWs] = {
      for {
        vertxWs <- wsClient.underlying.connect(wsPort, "127.0.0.1", "/ws").asScala
        ws = ClientWs(vertxWs, _ => ())
        // using underlying ws to test unexpected messages, WsClient does not allow that
        _ = vertxWs.textMessageHandler(clientProbe.ref ! _)
        _ <- vertxWs.writeTextMessage("invalid_msg").asScala
      } yield ws
    }

    def assertParsingError(clientProbe: TestProbe): Assertion = {
      inside(read[Response.Failure](clientProbe.expectMsgClass(classOf[String]))) {
        case JsonRPC.Response.Failure(error, _) =>
          error.code is Error.ParseErrorCode
      }
    }

    def invalidSubscriptionParamsBehavior(ws: ClientWs): Future[Unit] = {
      ws.underlying
        .writeTextMessage(write(WsRequest.subscribe(1, SimpleSubscribeParams(""))))
        .asScala
        .mapTo[Unit]
    }

    def assertInvalidSubscription(clientProbe: TestProbe): Assertion = {
      inside(read[Response](clientProbe.expectMsgClass(classOf[String]))) {
        case JsonRPC.Response.Success(_, _) =>
          fail("Should not receive success response")
        case JsonRPC.Response.Failure(error, _) =>
          error.code is Error.InvalidParamsCode
      }
    }

    def assertInvalidUnsubscription(clientProbe: TestProbe): Assertion = {
      inside(read[Response](clientProbe.expectMsgClass(classOf[String]))) {
        case JsonRPC.Response.Success(_, _) =>
          fail("Should not receive success response")
        case JsonRPC.Response.Failure(error, _) =>
          error.code is Error.InvalidParamsCode
      }
    }

    def invalidUnsubscriptionParamsBehavior(ws: ClientWs): Future[Unit] = {
      ws.underlying
        .writeTextMessage(write(WsRequest(Correlation(1), UnsubscribeParams(""))))
        .asScala
        .mapTo[Unit]
    }

    val wsInitBehavior = WsStartBehavior(invalidMessageBehavior, _ => (), assertParsingError)
    val wsNextBehaviors =
      AVector(
        WsNextBehavior(invalidSubscriptionParamsBehavior, _ => (), assertInvalidSubscription),
        WsNextBehavior(invalidUnsubscriptionParamsBehavior, _ => (), assertInvalidUnsubscription)
      )
    checkWS(
      initBehaviors = AVector.fill(1)(wsInitBehavior),
      nextBehaviors = wsNextBehaviors,
      expectedSubscriptions = 0,
      openWebsocketsCount = 1
    )
  }

  it should "subscribe/unsubscribe from block, tx and contract events from multiple addresses of different event indexes" in new WsBehaviorFixture {
    val contractEventsParams_0 = ContractEventsSubscribeParams.from(
      EventIndex_0,
      AVector(contractAddress_0, contractAddress_1)
    )
    val contractEventsParams_1 = ContractEventsSubscribeParams.from(
      EventIndex_1,
      AVector(contractAddress_1, contractAddress_2)
    )

    def subscribingBehavior(clientProbe: TestProbe): Future[ClientWs] = {
      for {
        ws                        <- wsClient.connect(wsPort)(ntf => clientProbe.ref ! ntf)
        blockSubscriptionResponse <- ws.subscribeToBlock(0)
        txSubscriptionResponse    <- ws.subscribeToTx(1)
        contractEventsSubscriptionResponse_0 <- ws.subscribeToContractEvents(
          2,
          contractEventsParams_0.eventIndex,
          contractEventsParams_0.addresses
        )
        contractEventsSubscriptionResponse_1 <- ws.subscribeToContractEvents(
          3,
          contractEventsParams_1.eventIndex,
          contractEventsParams_1.addresses
        )
      } yield {
        inside(blockSubscriptionResponse) { case JsonRPC.Response.Success(result, id) =>
          result is ujson.Str(SimpleSubscribeParams.Block.subscriptionId)
          id is 0
        }
        inside(txSubscriptionResponse) { case JsonRPC.Response.Success(result, id) =>
          result is ujson.Str(SimpleSubscribeParams.Tx.subscriptionId)
          id is 1
        }
        inside(contractEventsSubscriptionResponse_0) { case JsonRPC.Response.Success(result, id) =>
          result is ujson.Str(contractEventsParams_0.subscriptionId)
          id is 2
        }
        inside(contractEventsSubscriptionResponse_1) { case JsonRPC.Response.Success(result, id) =>
          result is ujson.Str(contractEventsParams_1.subscriptionId)
          id is 3
        }
        ws
      }
    }

    def assertCorrectNotificationResponse(clientProbe: TestProbe): Assertion = {
      val notifications = AVector.fill(5)(clientProbe.expectMsgType[Notification])
      val params        = notifications.map(n => read[WsNotificationParams](n.params))
      params.map(_.subscription).length is 5     // for 1 tx, 1 block and 3 addresses
      params.map(_.subscription).toSet.size is 4 // only 2 unique contract events subscriptions
    }

    def testResponse(correlationId: Long)(response: Response): Unit = {
      inside(response) { case JsonRPC.Response.Success(result, id) =>
        result is ujson.True
        id is correlationId
      }
      ()
    }

    def unsubscribingBehavior(ws: ClientWs): Future[Unit] = {
      ws.unsubscribeFromBlock(3).map(testResponse(correlationId = 3))
      ws.unsubscribeFromTx(4).map(testResponse(correlationId = 4))
      ws.unsubscribeFromContractEvents(5, contractEventsParams_0.subscriptionId)
        .map(testResponse(correlationId = 5))
      ws.unsubscribeFromContractEvents(6, contractEventsParams_1.subscriptionId)
        .map(testResponse(correlationId = 6))
    }

    val wsInitBehavior = WsStartBehavior(
      subscribingBehavior,
      eventBusRef => {
        eventBusRef ! BlockNotify(
          blockGen.sample.get,
          height = 0,
          logStatesFor(
            AVector(
              contractAddress_0.contractId -> EventIndex_0,
              contractAddress_1.contractId -> EventIndex_0,
              contractAddress_2.contractId -> EventIndex_1
            )
          )
        )
        eventBusRef ! TxNotify(transactionGen().sample.get.toTemplate, TimeStamp.now())
      },
      assertCorrectNotificationResponse
    )
    val wsNextBehavior = WsNextBehavior(
      unsubscribingBehavior,
      eventBusRef => {
        eventBusRef ! BlockNotify(
          blockGen.sample.get,
          height = 1,
          logStatesFor(AVector(contractAddress_0.contractId -> EventIndex_0))
        )
        eventBusRef ! TxNotify(transactionGen().sample.get.toTemplate, TimeStamp.now())
      },
      _.expectNoMessage()
    )
    checkWS(
      initBehaviors = AVector.fill(1)(wsInitBehavior),
      nextBehaviors = AVector.fill(1)(wsNextBehavior),
      expectedSubscriptions = 0, // unsubscribed
      openWebsocketsCount = 1    // unsubscribed but not disconnected
    )
  }

  it should "support subscription, unsubscription and disconnection" in new WsSubscriptionFixture
    with Eventually {
    val WsServer(httpServer, _, subscriptionHandler) = bindAndListen()
    eventually(testSubscriptionHandlerInitialized(subscriptionHandler))

    val ws = dummyServerWs("dummy")

    AVector(
      SimpleSubscribeParams.Block,
      SimpleSubscribeParams.Tx,
      ContractEventsSubscribeParams.from(EventIndex_0, AVector(contractAddress_0))
    ).fold(0L) { case (index, params) =>
      subscriptionHandler ! Subscribe(Correlation(index), ws, params)
      eventually {
        assertSubscribed(
          ws.textHandlerID(),
          params.subscriptionId,
          subscriptionHandler
        )
      }

      // it should be idempotent for subscriptions
      subscriptionHandler ! Subscribe(Correlation(index + 1), ws, params)
      eventually {
        assertSubscribed(
          ws.textHandlerID(),
          params.subscriptionId,
          subscriptionHandler
        )
      }

      subscriptionHandler ! Unsubscribe(Correlation(index + 2), ws, params.subscriptionId)
      eventually {
        assertConnectedButNotSubscribed(
          ws.textHandlerID(),
          params.subscriptionId,
          subscriptionHandler
        )
      }
      index + 3
    }

    subscriptionHandler ! Unregister(ws.textHandlerID())
    eventually(assertNotConnected(ws.textHandlerID(), subscriptionHandler))

    httpServer.close().asScala.futureValue
  }

}
