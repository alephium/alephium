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
import org.alephium.app.ws.WsParams.{SubscribeParams, UnsubscribeParams}
import org.alephium.app.ws.WsRequest.Correlation
import org.alephium.app.ws.WsSubscriptionHandler._
import org.alephium.app.ws.WsUtils._
import org.alephium.flow.handler.AllHandlers.{BlockNotify, TxNotify}
import org.alephium.json.Json._
import org.alephium.rpc.model.JsonRPC
import org.alephium.rpc.model.JsonRPC.{Error, Notification, Response}
import org.alephium.util._

class WsSubscriptionHandlerSpec extends AlephiumSpec with ServerFixture {
  import org.alephium.app.ws.WsBehaviorFixture._

  it should "connect and subscribe multiple ws clients to multiple events" in new WsBehaviorFixture {
    def subscribingRequestResponseBehavior(clientProbe: TestProbe): Future[ClientWs] = {
      for {
        ws                        <- wsClient.connect(wsPort)(ntf => clientProbe.ref ! ntf)
        blockSubscriptionResponse <- ws.subscribeToBlock(0)
        txSubscriptionResponse    <- ws.subscribeToTx(1)
      } yield {
        inside(blockSubscriptionResponse) { case JsonRPC.Response.Success(result, id) =>
          result is ujson.Str(SubscribeParams.Block.subscriptionId)
          id is 0
        }
        inside(txSubscriptionResponse) { case JsonRPC.Response.Success(result, id) =>
          result is ujson.Str(SubscribeParams.Tx.subscriptionId)
          id is 1
        }
        ws
      }
    }

    def serverBehavior(eventBusRef: ActorRefT[EventBus.Message]): Unit = {
      eventBusRef ! BlockNotify(blockGen.sample.get, height = 0, AVector.empty)
      eventBusRef ! TxNotify(transactionGen().sample.get.toTemplate, TimeStamp.now())
    }

    def assertValidNotification(clientProbe: TestProbe): Unit = {
      AVector.fill(2)(clientProbe.expectMsgType[Notification]).foreach { notification =>
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
      expectedSubscriptions = 3 * 2, // 3 clients, 2 subscriptions each
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
        .writeTextMessage(write(WsRequest.subscribe(1, SubscribeParams(""))))
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

  it should "handle unsubscribing from events" in new WsBehaviorFixture {
    def subscribingBehavior(clientProbe: TestProbe): Future[ClientWs] = {
      for {
        ws                        <- wsClient.connect(wsPort)(ntf => clientProbe.ref ! ntf)
        blockSubscriptionResponse <- ws.subscribeToBlock(0)
        txSubscriptionResponse    <- ws.subscribeToTx(1)
      } yield {
        inside(blockSubscriptionResponse) { case JsonRPC.Response.Success(result, id) =>
          result is ujson.Str(SubscribeParams.Block.subscriptionId)
          id is 0
        }
        inside(txSubscriptionResponse) { case JsonRPC.Response.Success(result, id) =>
          result is ujson.Str(SubscribeParams.Tx.subscriptionId)
          id is 1
        }
        ws
      }
    }

    def assertCorrectSubscribeResponse(clientProbe: TestProbe): Unit = {
      AVector.fill(2)(clientProbe.expectMsgType[Notification]).foreach { notification =>
        notification.method is WsMethod.SubscriptionMethod
        notification.params.obj.get("result").nonEmpty is true
        notification.params.obj.get("subscription").nonEmpty is true
      }
    }

    def unsubscribingBehavior(ws: ClientWs): Future[Unit] = {
      ws.unsubscribeFromBlock(1).map { blockSubscriptionResponse =>
        inside(blockSubscriptionResponse) { case JsonRPC.Response.Success(result, id) =>
          result is ujson.True
          id is 1
        }
        ()
      }
      ws.unsubscribeFromTx(2).map { txSubscriptionResponse =>
        inside(txSubscriptionResponse) { case JsonRPC.Response.Success(result, id) =>
          result is ujson.True
          id is 2
        }
        ()
      }
    }

    val wsInitBehavior = WsStartBehavior(
      subscribingBehavior,
      eventBusRef => {
        eventBusRef ! BlockNotify(blockGen.sample.get, height = 0, AVector.empty)
        eventBusRef ! TxNotify(transactionGen().sample.get.toTemplate, TimeStamp.now())
      },
      assertCorrectSubscribeResponse
    )
    val wsNextBehavior = WsNextBehavior(
      unsubscribingBehavior,
      eventBusRef => {
        eventBusRef ! BlockNotify(blockGen.sample.get, height = 1, AVector.empty)
        eventBusRef ! TxNotify(transactionGen().sample.get.toTemplate, TimeStamp.now())
      },
      _.expectNoMessage()
    )
    checkWS(
      initBehaviors = AVector.fill(1)(wsInitBehavior),
      nextBehaviors = AVector.fill(1)(wsNextBehavior),
      expectedSubscriptions = 0,
      openWebsocketsCount = 1
    )
  }

  it should "support subscription, unsubscription and disconnection" in new WsSubscriptionFixture
    with Eventually {
    val WsServer(httpServer, _, subscriptionHandler) = bindAndListen()
    eventually(testSubscriptionHandlerInitialized(subscriptionHandler))

    val ws = dummyServerWs("dummy")

    AVector(
      SubscribeParams.Block,
      SubscribeParams.Tx
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
