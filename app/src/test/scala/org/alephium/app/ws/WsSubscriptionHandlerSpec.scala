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

import akka.testkit.TestProbe
import org.scalatest.Assertion
import org.scalatest.Inside.inside
import org.scalatest.concurrent.Eventually

import org.alephium.app.ServerFixture
import org.alephium.app.ws.WsParams.{SubscribeParams, UnsubscribeParams}
import org.alephium.app.ws.WsRequest.Correlation
import org.alephium.app.ws.WsSubscriptionHandler._
import org.alephium.app.ws.WsUtils._
import org.alephium.flow.handler.AllHandlers.BlockNotify
import org.alephium.json.Json._
import org.alephium.rpc.model.JsonRPC
import org.alephium.rpc.model.JsonRPC.{Error, NotificationUnsafe, Response}
import org.alephium.util._

class WsSubscriptionHandlerSpec extends AlephiumSpec with ServerFixture {
  import org.alephium.app.ws.WsBehaviorFixture._

  it should "connect and subscribe multiple ws clients to multiple events" in new WsBehaviorFixture {
    def subscribingRequestResponseBehavior(ws: Ws, clientProbe: TestProbe): Unit = {
      ws.textMessageHandler(message => clientProbe.ref ! message)
      (for {
        _ <- ws.subscribeToBlock(0)
        _ <- ws.subscribeToTx(1)
      } yield ()).futureValue
      inside(read[Response](clientProbe.expectMsgClass(classOf[String]))) {
        case JsonRPC.Response.Success(result, id) =>
          result is ujson.Str(SubscribeParams.Block.subscriptionId)
          id is 0
      }
      inside(read[Response](clientProbe.expectMsgClass(classOf[String]))) {
        case JsonRPC.Response.Success(result, id) =>
          result is ujson.Str(SubscribeParams.Tx.subscriptionId)
          id is 1
      }
      ()
    }

    def serverBehavior(eventBusRef: ActorRefT[EventBus.Message]): Unit = {
      eventBusRef ! BlockNotify(blockGen.sample.get, height = 0)
    }

    def assertValidNotification(clientProbe: TestProbe): Assertion = {
      val notification =
        read[NotificationUnsafe](
          clientProbe.expectMsgClass(classOf[String])
        ).asNotification.rightValue
      notification.method is WsMethod.SubscriptionMethod
      notification.params.obj.get("result").nonEmpty is true
      notification.params.obj.get("subscription").nonEmpty is true
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
    val wsSpec = WsStartBehavior((_, _) => (), _ => (), _ => true is true)
    checkWS(
      initBehaviors = AVector.fill(3)(wsSpec),
      nextBehaviors = AVector.empty,
      expectedSubscriptions = 0,
      openWebsocketsCount = 3
    )
  }

  it should "connect and not subscribe multiple ws clients to any event" in new WsBehaviorFixture {
    def clientInitBehavior(ws: Ws, clientProbe: TestProbe): Unit = {
      val _ = ws.textMessageHandler(message => clientProbe.ref ! message)
    }

    val wsInitBehavior = WsStartBehavior(
      clientInitBehavior,
      _ ! BlockNotify(blockGen.sample.get, height = 0),
      _.expectNoMessage()
    )
    checkWS(
      initBehaviors = AVector.fill(3)(wsInitBehavior),
      nextBehaviors = AVector.empty,
      expectedSubscriptions = 0,
      openWebsocketsCount = 3
    )
  }

  it should "handle invalid subscription and unsubscription requests with error" in new WsBehaviorFixture {
    def invalidMessageBehavior(ws: Ws, clientProbe: TestProbe): Unit = {
      ws.textMessageHandler(message => clientProbe.ref ! message)
      ws.writeTextMessage("invalid_msg").futureValue
    }

    def assertParsingError(clientProbe: TestProbe): Assertion = {
      inside(read[Response.Failure](clientProbe.expectMsgClass(classOf[String]))) {
        case JsonRPC.Response.Failure(error, _) =>
          error.code is Error.ParseErrorCode
      }
    }

    def invalidSubscriptionParamsBehavior(ws: Ws): Unit = {
      ws.writeTextMessage(write(WsRequest.subscribe(1, SubscribeParams("")))).futureValue
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

    def invalidUnsubscriptionParamsBehavior(ws: Ws): Unit = {
      ws.writeTextMessage(write(WsRequest(Correlation(1), UnsubscribeParams("")))).futureValue
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
    def subscribingBehavior(ws: Ws, clientProbe: TestProbe): Unit = {
      ws.textMessageHandler(message => clientProbe.ref ! message)
      ws.subscribeToBlock(0).futureValue
    }
    def assertCorrectSubscribeResponse(clientProbe: TestProbe): Assertion = {
      inside(read[Response](clientProbe.expectMsgClass(classOf[String]))) {
        case JsonRPC.Response.Success(result, id) =>
          result is ujson.Str(SubscribeParams.Block.subscriptionId)
          id is 0
      }
      val notification =
        read[NotificationUnsafe](
          clientProbe.expectMsgClass(classOf[String])
        ).asNotification.rightValue
      notification.method is WsMethod.SubscriptionMethod
      notification.params.obj.get("result").nonEmpty is true
      notification.params.obj.get("subscription").nonEmpty is true
    }

    def assertCorrectUnsubscribeResponse(clientProbe: TestProbe): Assertion = {
      inside(read[Response](clientProbe.expectMsgClass(classOf[String]))) {
        case JsonRPC.Response.Success(result, id) =>
          result is ujson.True
          id is 1
      }
    }

    val wsInitBehavior = WsStartBehavior(
      subscribingBehavior,
      _ ! BlockNotify(blockGen.sample.get, height = 0),
      assertCorrectSubscribeResponse
    )
    val wsNextBehavior = WsNextBehavior(
      _.unsubscribeFromBlock(1).futureValue,
      _ ! BlockNotify(blockGen.sample.get, height = 1),
      assertCorrectUnsubscribeResponse
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
    subscriptionHandler ! Subscribe(Correlation(0), ws, SubscribeParams.Block)

    eventually {
      assertSubscribed(
        ws.textHandlerID(),
        SubscribeParams.Block.subscriptionId,
        subscriptionHandler
      )
    }

    // it should be idempotent for subscriptions
    subscriptionHandler ! Subscribe(Correlation(1), ws, SubscribeParams.Block)
    eventually {
      assertSubscribed(
        ws.textHandlerID(),
        SubscribeParams.Block.subscriptionId,
        subscriptionHandler
      )
    }

    subscriptionHandler ! Unsubscribe(Correlation(2), ws, SubscribeParams.Block.subscriptionId)
    eventually {
      assertConnectedButNotSubscribed(
        ws.textHandlerID(),
        SubscribeParams.Block.subscriptionId,
        subscriptionHandler
      )
    }

    subscriptionHandler ! Unregister(ws.textHandlerID())
    eventually(assertNotConnected(ws.textHandlerID(), subscriptionHandler))

    httpServer.close().asScala.futureValue
  }

}
