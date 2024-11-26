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

import scala.collection.mutable
import scala.concurrent.Future

import akka.testkit.TestProbe
import io.vertx.core.http.WebSocket
import org.scalatest.{Assertion, EitherValues}

import org.alephium.app.VertxFutureConverter._
import org.alephium.app.WebSocketServer.{EventHandler, WsCommand, WsEvent, WsMethod}
import org.alephium.flow.handler.AllHandlers.BlockNotify
import org.alephium.json.Json._
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util._

class WebSocketServerSpec extends AlephiumFutureSpec with EitherValues with NumericHelpers {

  behavior of "WebSocketServer"

  it should "subscribe event handler into event bus" in new WebSocketServerFixture {
    val newHandler = EventHandler.getSubscribedEventHandler(vertx.eventBus(), node.eventBus, system)
    node.eventBus
      .ask(EventBus.ListSubscribers)
      .mapTo[EventBus.Subscribers]
      .futureValue
      .value
      .contains(newHandler.ref) is true
  }

  it should "connect and subscribe multiple ws clients to multiple events" in new RouteWS {
    def clientInitBehavior(ws: WebSocket, clientProbe: TestProbe): Future[Unit] = {
      ws.textMessageHandler(message => clientProbe.ref ! message)
      for {
        _ <- ws.writeTextMessage(WsEvent(WsCommand.Subscribe, WsMethod.Block).toString).asScala
        _ <- ws.writeTextMessage(WsEvent(WsCommand.Subscribe, WsMethod.Tx).toString).asScala
      } yield ()
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

    val wsInitBehavior = WsStartBehavior(clientInitBehavior, serverBehavior, clientAssertionOnMsg)
    checkWS(
      initBehaviors = AVector.fill(3)(wsInitBehavior),
      nextBehaviors = AVector.empty,
      expectedSubscriptions = 3 * 2, // 3 clients, 2 subscriptions each
      openWebsocketsCount = 3
    )
  }

  it should "not spin ws connections over limit" in new RouteWS {
    override def maxConnections: Int = 2
    val wsSpec = WsStartBehavior((_, _) => Future.successful(()), _ => (), _ => true is true)
    checkWS(
      initBehaviors = AVector.fill(3)(wsSpec),
      nextBehaviors = AVector.empty,
      expectedSubscriptions = 0,
      openWebsocketsCount = 3
    )
  }

  it should "connect and not subscribe multiple ws clients to any event" in new RouteWS {
    def clientInitBehavior(ws: WebSocket, clientProbe: TestProbe): Future[Unit] = {
      ws.textMessageHandler(message => clientProbe.ref ! message)
      Future.successful(())
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

  it should "handle invalid messages gracefully" in new RouteWS {
    def clientInitBehavior(ws: WebSocket, clientProbe: TestProbe): Future[Unit] = {
      ws.textMessageHandler(message => clientProbe.ref ! message)
      ws.writeTextMessage("invalid_msg").asScala.map(_ => ())
    }

    def clientAssertionOnMsg(clientProbe: TestProbe): Assertion =
      clientProbe.expectMsgPF() { case msg: String =>
        msg is "Unsupported message : invalid_msg"
      }

    val wsInitBehavior = WsStartBehavior(clientInitBehavior, _ => (), clientAssertionOnMsg)
    checkWS(
      initBehaviors = AVector.fill(1)(wsInitBehavior),
      nextBehaviors = AVector.empty,
      expectedSubscriptions = 0,
      openWebsocketsCount = 1
    )
  }

  it should "handle unsubscribing from events" in new RouteWS {
    def clientInitBehavior(ws: WebSocket, clientProbe: TestProbe): Future[Unit] = {
      ws.textMessageHandler(message => clientProbe.ref ! message)
      ws.writeTextMessage(WsEvent(WsCommand.Subscribe, WsMethod.Block).toString)
        .asScala
        .map(_ => ())
    }
    def clientInitAssertionOnMsg(clientProbe: TestProbe): Assertion =
      clientProbe.expectMsgPF() { case msg: String =>
        read[NotificationUnsafe](
          msg
        ).asNotification.rightValue.method is WsMethod.Block.name
      }
    def clientNextBehavior(ws: WebSocket): Future[Unit] = {
      ws.writeTextMessage(WsEvent(WsCommand.Unsubscribe, WsMethod.Block).toString)
        .asScala
        .map(_ => ())
    }
    val wsInitBehavior = WsStartBehavior(
      clientInitBehavior,
      _ ! BlockNotify(blockGen.sample.get, height = 0),
      clientInitAssertionOnMsg
    )
    val wsNextBehavior = WsBehavior(
      clientNextBehavior,
      _ ! BlockNotify(blockGen.sample.get, height = 1),
      _.expectNoMessage()
    )
    checkWS(
      initBehaviors = AVector.fill(1)(wsInitBehavior),
      nextBehaviors = AVector.fill(1)(wsNextBehavior),
      expectedSubscriptions = 0,
      openWebsocketsCount = 1
    )
  }

  "richMap" should "handle" in {
    val richMap = mutable.Map("a" -> 0, "b" -> 0)

    // Increment value of existing key "a"
    richMap.updateWith("a") { case Some(v) => Some(v + 1); case None => None } is Some(1)
    richMap is mutable.Map("a" -> 1, "b" -> 0)

    // Add new key "c" with value 1
    richMap.updateWith("c") { case None => Some(1); case Some(_) => None } is Some(1)
    richMap is mutable.Map("a" -> 1, "b" -> 0, "c" -> 1)

    // No change for non-existent key "d" with None returned
    richMap.updateWith("d") { case None => None; case Some(_) => None } is None
    richMap is mutable.Map("a" -> 1, "b" -> 0, "c" -> 1)

    // Remove existing key "c"
    richMap.updateWith("c") { case Some(_) => None; case None => None } is None
    richMap is mutable.Map("a" -> 1, "b" -> 0)
  }

}
