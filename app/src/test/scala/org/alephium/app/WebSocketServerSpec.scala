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

package org.alephium.app

import akka.testkit.TestProbe
import io.vertx.core.http.WebSocket
import org.scalatest.{Assertion, EitherValues}

import org.alephium.app.WebSocketServer.{EventHandler, WsCommand, WsEvent, WsMethod}
import org.alephium.flow.handler.AllHandlers.BlockNotify
import org.alephium.json.Json._
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util._

class WebSocketServerSpec extends AlephiumFutureSpec with EitherValues with NumericHelpers {

  behavior of "ws"

  it should "subscribe" in new WebSocketServerFixture {
    val eventHandlerRef =
      EventHandler.getSubscribedEventHandlerRef(vertx.eventBus(), node.eventBus, system)
    node.eventBus
      .ask(EventBus.ListSubscribers)
      .mapTo[EventBus.Subscribers]
      .futureValue
      .value
      .contains(eventHandlerRef) is true
  }

  it should "connect and subscribe multiple ws clients to multiple events" in new RouteWS {
    def clientInitBehavior(ws: WebSocket, clientProbe: TestProbe): (WebSocket, TestProbe) = {
      ws.textMessageHandler { message =>
        clientProbe.ref ! message
      }
      ws.writeTextMessage(WsEvent(WsCommand.Subscribe, WsMethod.Block).toString)
      ws.writeTextMessage(WsEvent(WsCommand.Subscribe, WsMethod.Tx).toString)
      ws -> clientProbe
    }

    def serverBehavior(eventBusRef: ActorRefT[EventBus.Message]): Unit = {
      eventBusRef ! BlockNotify(blockGen.sample.get, height = 0)
    }

    def clientAssertionOnMsg(clientProbe: TestProbe): Assertion =
      clientProbe.expectMsgPF() { case msg: String =>
        read[NotificationUnsafe](
          msg
        ).asNotification.rightValue.method is WsMethod.Block.name
      }

    val wsSpec = WebSocketSpec(clientInitBehavior, serverBehavior, clientAssertionOnMsg)
    checkWS(AVector.fill(3)(wsSpec), subscribersCount = 3, openWebsocketsCount = 3)
  }

  it should "not spin ws connections over limit" in new RouteWS {
    override def maxConnections: Int = 2
    val wsSpec = WebSocketSpec((ws, clientProbe) => (ws, clientProbe), _ => (), _ => true is true)
    checkWS(AVector.fill(3)(wsSpec), subscribersCount = 0, openWebsocketsCount = 3)
  }

  it should "connect and not subscribe multiple ws clients to any event" in new RouteWS {
    def clientInitBehavior(ws: WebSocket, clientProbe: TestProbe): (WebSocket, TestProbe) = {
      ws.textMessageHandler { message =>
        clientProbe.ref ! message
      }
      ws -> clientProbe
    }

    val wsSpec = WebSocketSpec(
      clientInitBehavior,
      _ ! BlockNotify(blockGen.sample.get, height = 0),
      _.expectNoMessage()
    )
    checkWS(AVector.fill(3)(wsSpec), subscribersCount = 0, openWebsocketsCount = 3)
  }

  it should "handle invalid messages gracefully" in new RouteWS {
    def clientInitBehavior(ws: WebSocket, clientProbe: TestProbe): (WebSocket, TestProbe) = {
      ws.textMessageHandler { message =>
        clientProbe.ref ! message
      }
      ws.writeTextMessage("invalid_msg")
      ws -> clientProbe
    }

    def clientAssertionOnMsg(clientProbe: TestProbe): Assertion =
      clientProbe.expectMsgPF() { case msg: String =>
        msg is "Unsupported message : invalid_msg"
      }

    val wsSpec = WebSocketSpec(clientInitBehavior, _ => (), clientAssertionOnMsg)
    checkWS(AVector.fill(1)(wsSpec), subscribersCount = 0, openWebsocketsCount = 1)
  }

  it should "handle unsubscribing from events" in new RouteWS {
    def clientInitBehavior(ws: WebSocket, clientProbe: TestProbe): (WebSocket, TestProbe) = {
      ws.textMessageHandler { message =>
        clientProbe.ref ! message
      }
      ws.writeTextMessage(WsEvent(WsCommand.Subscribe, WsMethod.Block).toString)
      ws.writeTextMessage(WsEvent(WsCommand.Unsubscribe, WsMethod.Block).toString)
      ws -> clientProbe
    }

    val wsSpec = WebSocketSpec(
      clientInitBehavior,
      _ ! BlockNotify(blockGen.sample.get, height = 0),
      _.expectNoMessage()
    )
    checkWS(AVector.fill(1)(wsSpec), subscribersCount = 0, openWebsocketsCount = 1)
  }

}
