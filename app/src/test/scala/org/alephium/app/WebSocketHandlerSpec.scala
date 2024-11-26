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

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}

import io.vertx.core.{Future => VertxFuture}
import org.scalatest.concurrent.ScalaFutures.convertScalaFuture

import org.alephium.api.model.BlockEntry
import org.alephium.app.WebSocketServer.WsEventHandler
import org.alephium.flow.handler.AllHandlers.BlockNotify
import org.alephium.json.Json._
import org.alephium.util._

class WebSocketHandlerSpec extends AlephiumSpec with ServerFixture with WsUtils {
  import ServerFixture._

  behavior of "WsEventHandler"

  it should "subscribe event handler into event bus" in new WebSocketServerFixture {
    val newHandler =
      WsEventHandler.getSubscribedEventHandler(vertx.eventBus(), node.eventBus, system)
    node.eventBus
      .ask(EventBus.ListSubscribers)
      .mapTo[EventBus.Subscribers]
      .futureValue
      .value
      .contains(newHandler.ref) is true
  }

  it should "build Notification from Event" in {
    val notification = WsEventHandler.buildNotification(BlockNotify(dummyBlock, 0)).rightValue
    val blockEntry   = BlockEntry.from(dummyBlock, 0).rightValue
    notification.method is WsMethod.Block.name
    show(notification.params) is write(blockEntry)
  }

  "WsProtocol" should "parse websocket event for subscription and unsubscription" in {
    WsMethod.values.foreach { method =>
      WsCommand.values.foreach { command =>
        WsEvent.parseEvent(s"$command:$method").get is WsEvent(command, method)
        WsEvent.parseEvent(s"$command:xxx:$method").isEmpty is true
        WsEvent.parseEvent(s"$command : $method").isEmpty is true
        WsEvent.parseEvent(s"$command,$method").isEmpty is true
        WsEvent.parseEvent(s"$command $method").isEmpty is true
        WsEvent.parseEvent(s"$command:gandalf").isEmpty is true
        WsEvent.parseEvent("nonsense:frodo").isEmpty is true
      }
    }
  }

  "WsUtils" should "convert VertxFuture to Scala Future" in {
    val successfulVertxFuture: VertxFuture[String] = VertxFuture.succeededFuture("Success Result")
    val successfulScalaFuture: Future[String]      = successfulVertxFuture.asScala
    successfulScalaFuture.onComplete {
      case Success(value) =>
        value is "Success Result"
      case Failure(_) =>
        fail("The future should not fail in this test case")
    }

    val exception                              = new RuntimeException("Test Failure")
    val failedVertxFuture: VertxFuture[String] = VertxFuture.failedFuture(exception)
    val failedScalaFuture: Future[String]      = failedVertxFuture.asScala

    failedScalaFuture.onComplete {
      case Success(_) =>
        fail("The future should not succeed in this test case")
      case Failure(ex) =>
        ex is exception // Expected exception is thrown
    }
  }
}
