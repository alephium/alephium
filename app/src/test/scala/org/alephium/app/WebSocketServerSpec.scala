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

import scala.concurrent.Future

import akka.testkit.TestProbe
import io.vertx.core.http.WebSocket
import org.scalatest.{Assertion, EitherValues}

import org.alephium.flow.handler.AllHandlers.BlockNotify
import org.alephium.json.Json._
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util._

class WebSocketServerSpec
    extends AlephiumFutureSpec
    with EitherValues
    with NumericHelpers
    with WsUtils {

  behavior of "WebSocketServer"

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

}
