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
import io.vertx.core.http.WebSocket
import org.scalatest.Assertion
import org.scalatest.Inside.inside
import org.scalatest.concurrent.Eventually

import org.alephium.app.ServerFixture
import org.alephium.app.ws.WsParams.SubscribeParams
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
    def clientInitBehavior(ws: WebSocket, clientProbe: TestProbe): Unit = {
      ws.textMessageHandler(message => clientProbe.ref ! message)
      (for {
        _ <- ws
          .writeTextMessage(
            write(WsRequest.subscribe(0, SubscribeParams.Block))
          )
          .asScala
        _ <- ws
          .writeTextMessage(
            write(WsRequest.subscribe(1, SubscribeParams.Tx))
          )
          .asScala
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

    def clientAssertionOnMsg(clientProbe: TestProbe): Assertion = {
      val notification =
        read[NotificationUnsafe](
          clientProbe.expectMsgClass(classOf[String])
        ).asNotification.rightValue
      notification.method is WsMethod.SubscriptionMethod
      notification.params.obj.get("result").nonEmpty is true
      notification.params.obj.get("subscription").nonEmpty is true
    }

    val wsInitBehavior = WsStartBehavior(clientInitBehavior, serverBehavior, clientAssertionOnMsg)
    checkWS(
      initBehaviors = AVector.fill(3)(wsInitBehavior),
      nextBehaviors = AVector.empty,
      expectedSubscriptions = 3 * 2, // 3 clients, 2 subscriptions each
      openWebsocketsCount = 3
    )
  }

  it should "not spin ws connections over limit" in new WsBehaviorFixture {
    override def maxConnections: Int = 2
    val wsSpec                       = WsStartBehavior((_, _) => (), _ => (), _ => true is true)
    checkWS(
      initBehaviors = AVector.fill(3)(wsSpec),
      nextBehaviors = AVector.empty,
      expectedSubscriptions = 0,
      openWebsocketsCount = 3
    )
  }

  it should "connect and not subscribe multiple ws clients to any event" in new WsBehaviorFixture {
    def clientInitBehavior(ws: WebSocket, clientProbe: TestProbe): Unit = {
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

  it should "handle invalid messages gracefully" in new WsBehaviorFixture {
    def clientInitBehavior(ws: WebSocket, clientProbe: TestProbe): Unit = {
      ws.textMessageHandler(message => clientProbe.ref ! message)
      ws.writeTextMessage("invalid_msg").asScala.mapTo[Unit].futureValue
    }

    def clientAssertionOnMsg(clientProbe: TestProbe): Assertion =
      clientProbe
        .expectMsgClass(classOf[String])
        .contains(Error.ParseErrorCode.toString) is true

    val wsInitBehavior = WsStartBehavior(clientInitBehavior, _ => (), clientAssertionOnMsg)
    checkWS(
      initBehaviors = AVector.fill(1)(wsInitBehavior),
      nextBehaviors = AVector.empty,
      expectedSubscriptions = 0,
      openWebsocketsCount = 1
    )
  }

  it should "handle unsubscribing from events" in new WsBehaviorFixture {
    def clientInitBehavior(ws: WebSocket, clientProbe: TestProbe): Unit = {
      ws.textMessageHandler(message => clientProbe.ref ! message)
      ws.writeTextMessage(write(WsRequest.subscribe(0, SubscribeParams.Block)))
        .asScala
        .mapTo[Unit]
        .futureValue
    }
    def clientInitAssertionOnMsg(clientProbe: TestProbe): Assertion = {
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

    def clientNextBehavior(ws: WebSocket): Unit = {
      ws.writeTextMessage(
        write(WsRequest.unsubscribe(1, SubscribeParams.Block.subscriptionId))
      ).asScala
        .mapTo[Unit]
        .futureValue
    }
    def clientNextAssertionOnMsg(clientProbe: TestProbe): Assertion = {
      inside(read[Response](clientProbe.expectMsgClass(classOf[String]))) {
        case JsonRPC.Response.Success(result, id) =>
          result is ujson.True
          id is 1
      }
    }

    val wsInitBehavior = WsStartBehavior(
      clientInitBehavior,
      _ ! BlockNotify(blockGen.sample.get, height = 0),
      clientInitAssertionOnMsg
    )
    val wsNextBehavior = WsNextBehavior(
      clientNextBehavior,
      _ ! BlockNotify(blockGen.sample.get, height = 1),
      clientNextAssertionOnMsg
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
