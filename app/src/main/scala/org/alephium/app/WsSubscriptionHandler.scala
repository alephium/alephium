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
import scala.util.Failure

import com.typesafe.scalalogging.StrictLogging
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Vertx
import io.vertx.core.eventbus.{Message, MessageConsumer}
import io.vertx.core.http.ServerWebSocket

import org.alephium.app.WebSocketServer.{Subscriber, SubscriberId}
import org.alephium.util.{AVector, BaseActor}

object WsSubscriptionHandler extends StrictLogging {

  sealed trait WsSubscription {
    def method: String
  }

  sealed trait SubscriptionMsg
  sealed trait Command                                          extends SubscriptionMsg
  sealed trait Response                                         extends SubscriptionMsg
  final case class ConnectAndSubscribe(socket: ServerWebSocket) extends Command
  final case object GetSubscriptions                            extends Command
  final case class SubscriptionResponse(
      subscriptions: AVector[(SubscriberId, AVector[(WsMethod, Subscriber)])]
  ) extends Response
}

class WsSubscriptionHandler(vertx: Vertx, maxConnections: Int) extends BaseActor with WsUtils {
  import org.alephium.app.WsSubscriptionHandler._

  // subscriber with empty subscriptions is connected but not subscribed to any events
  private val subscribers = mutable.Map.empty[SubscriberId, AVector[(WsMethod, Subscriber)]]

  override def receive: Receive = {
    case ConnectAndSubscribe(ws) =>
      if (subscribers.size >= maxConnections) {
        log.warning(s"WebSocket connections reached max limit ${subscribers.size}")
        ws.reject(HttpResponseStatus.TOO_MANY_REQUESTS.code())
      } else if (ws.path().equals("/ws")) {
        ws.closeHandler { _ =>
          val _ = unSubscribeAll(ws.textHandlerID())
        }
        val _ = ws.textMessageHandler(msg => handleMessage(ws, msg))
      } else {
        ws.reject(HttpResponseStatus.BAD_REQUEST.code())
      }
    case GetSubscriptions =>
      sender() ! SubscriptionResponse(AVector.from(subscribers))
  }

  private def handleMessage(ws: ServerWebSocket, message: String): Unit = {
    WsEvent.parseEvent(message) match {
      case Some(WsEvent(WsCommand.Subscribe, method)) =>
        val _ = subscribe(ws, method)
      case Some(WsEvent(WsCommand.Unsubscribe, method)) =>
        val _ = unSubscribe(ws.textHandlerID(), method)
      case None =>
        respondWith(ws, s"Unsupported message : $message")
    }
  }

  private def subscribe(
      ws: ServerWebSocket,
      method: WsMethod
  ): Option[AVector[(WsMethod, Subscriber)]] = {
    subscribers.updateWith(ws.textHandlerID()) {
      case Some(ss) if ss.exists(_._1 == method) => Some(ss)
      case Some(ss) => Some(ss :+ (method -> subscribeToEvents(ws, method)))
      case None     => Some(AVector(method -> subscribeToEvents(ws, method)))
    }
  }

  private def unSubscribeAll(id: SubscriberId): Option[AVector[(WsMethod, Subscriber)]] = {
    subscribers.get(id).foreach { consumers =>
      consumers.foreach { case (_, consumer) => unSubscribeToEvents(consumer) }
    }
    subscribers.remove(id)
  }

  private def unSubscribe(
      id: SubscriberId,
      method: WsMethod
  ): Option[AVector[(WsMethod, Subscriber)]] = {
    subscribers.updateWith(id) {
      case Some(ss) =>
        val (toRemove, toKeep) = ss.partition(_._1 == method)
        toRemove.foreach { case (_, c) => unSubscribeToEvents(c) }
        Some(toKeep)
      case None => None
    }
  }

  private def respondWith(ws: ServerWebSocket, message: String): Unit = {
    val _ = ws
      .writeTextMessage(message)
      .asScala
      .andThen { case Failure(exception) =>
        log.warning(exception, s"Failed to write message: $message")
      }(context.dispatcher)
  }

  private def subscribeToEvents(
      ws: ServerWebSocket,
      method: WsMethod
  ): MessageConsumer[String] = {
    vertx
      .eventBus()
      .consumer[String](
        method.name,
        new io.vertx.core.Handler[Message[String]] {
          override def handle(message: Message[String]): Unit = {
            if (!ws.isClosed) {
              val _ = ws
                .writeTextMessage(message.body())
                .asScala
                .andThen { case Failure(exception) =>
                  log.warning(exception, s"Failed to write message: $message")
                }(context.dispatcher)
            }
          }
        }
      )
  }

  private def unSubscribeToEvents(consumer: MessageConsumer[String]): Unit = {
    val _ = consumer
      .unregister()
      .asScala
      .andThen { case Failure(exception) =>
        log.warning(exception, "Failed to unsubscribe event consumer")
      }(context.dispatcher)
  }

}
