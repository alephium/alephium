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
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Vertx
import io.vertx.core.eventbus.Message
import io.vertx.core.http.ServerWebSocket
import org.alephium.app.WebSocketServer.{SubscriberId, Subscription}
import org.alephium.json.Json.writeJs
import org.alephium.rpc.model.JsonRPC.Notification
import org.alephium.util.{AVector, BaseActor}

object WsSubscriptionHandler {

  sealed trait WsSubscription {
    def method: String
  }

  sealed trait SubscriptionMsg
  sealed trait Command  extends SubscriptionMsg
  sealed trait Event    extends SubscriptionMsg
  sealed trait Response extends SubscriptionMsg

  final case class ConnectAndSubscribe(socket: ServerWebSocket) extends Command
  final case object GetSubscriptions                            extends Command

  final private case class Subscribed(ws: ServerWebSocket, method: WsMethod, consumer: Subscription)
      extends Event
  final private case class AlreadySubscribed(ws: ServerWebSocket, method: WsMethod) extends Event
  final private case class SubscriptionFailed(ws: ServerWebSocket, method: WsMethod, err: String)
      extends Event
  final private case class Unsubscribed(ws: ServerWebSocket, method: WsMethod)        extends Event
  final private case class AlreadyUnSubscribed(ws: ServerWebSocket, method: WsMethod) extends Event
  final private case class UnsubscriptionFailed(ws: ServerWebSocket, method: WsMethod, err: String)
      extends Event
  final private case class NotificationFailed(ws: ServerWebSocket, method: WsMethod, msg: String)
      extends Event
  final private case class Unregistered(id: SubscriberId) extends Event

  final case class SubscriptionsResponse(
      subscriptions: AVector[(SubscriberId, AVector[(WsMethod, Subscription)])]
  ) extends Response
}

class WsSubscriptionHandler(vertx: Vertx, maxConnections: Int) extends BaseActor with WsUtils {
  import org.alephium.app.WsSubscriptionHandler._
  implicit private val ec: ExecutionContextExecutor = context.dispatcher

  // subscriber with empty subscriptions is connected but not subscribed to any events
  private val subscribers = mutable.Map.empty[SubscriberId, AVector[(WsMethod, Subscription)]]

  override def receive: Receive = {
    case ConnectAndSubscribe(ws) =>
      if (subscribers.size >= maxConnections) {
        log.warning(s"WebSocket connections reached max limit ${subscribers.size}")
        ws.reject(HttpResponseStatus.TOO_MANY_REQUESTS.code())
      } else {
        ws.closeHandler(_ => unregister(ws.textHandlerID()))
        val _ = ws.textMessageHandler(msg => handleMessage(ws, msg))
      }
    case Subscribed(ws, method, consumer) =>
      subscribers.updateWith(ws.textHandlerID()) {
        case Some(ss) if ss.exists(_._1 == method) =>
          Some(ss)
        case Some(ss) =>
          Some(ss :+ (method -> consumer))
        case None =>
          Some(AVector(method -> consumer))
      }
      respondAsyncAndIgnoreFailure(ws, Notification(WsMethod.Block.name, writeJs(blockEntry))s"Subscribed to $method subscription")
    case AlreadySubscribed(ws, method) =>
      respondAsyncAndIgnoreFailure(ws, s"Already subscribed to $method subscription")
    case SubscriptionFailed(ws, method, reason) =>
      respondAsyncAndIgnoreFailure(ws, s"Subscription from $method failed due to $reason")
    case Unsubscribed(ws, method) =>
      subscribers.updateWith(ws.textHandlerID())(_.map(_.filterNot(_._1 == method)))
      respondAsyncAndIgnoreFailure(ws, s"Unsubscribed from $method subscription")
    case AlreadyUnSubscribed(ws, method) =>
      respondAsyncAndIgnoreFailure(ws, s"Already unsubscribed from $method subscription")
    case UnsubscriptionFailed(ws, method, reason) =>
      respondAsyncAndIgnoreFailure(ws, s"Unsubscription from $method failed due to $reason")
    case NotificationFailed(_, _, _) =>
    // TODO can we do something about failing notification to client which is not closed ?
    case Unregistered(id) =>
      val _ = subscribers.remove(id)
    case GetSubscriptions =>
      sender() ! SubscriptionsResponse(AVector.from(subscribers))
  }

  private def handleMessage(ws: ServerWebSocket, message: String)(implicit
      ec: ExecutionContext
  ): Unit = {
    WsEvent.parseEvent(message) match {
      case Some(WsEvent(WsCommand.Subscribe, method)) =>
        val _ = subscribe(ws, method)
      case Some(WsEvent(WsCommand.Unsubscribe, method)) =>
        val _ = unSubscribe(ws, method)
      case None =>
        respondAsyncAndIgnoreFailure(ws, s"Unsupported message : $message")
    }
  }

  private def subscribe(ws: ServerWebSocket, method: WsMethod)(implicit
      ec: ExecutionContext
  ): Unit = {
    subscribers.get(ws.textHandlerID()) match {
      case Some(ss) if ss.exists(_._1 == method) =>
        self ! AlreadySubscribed(ws, method)
      case _ =>
        subscribeToEvents(ws, method) match {
          case Success(consumer) =>
            self ! Subscribed(ws, method, consumer)
          case Failure(ex) =>
            self ! SubscriptionFailed(ws, method, ex.getMessage)
        }
    }
  }

  private def unSubscribe(ws: ServerWebSocket, method: WsMethod): Unit = {
    subscribers.get(ws.textHandlerID()).map(_.filter(_._1 == method)) match {
      case Some(subscriptions) if subscriptions.nonEmpty =>
        subscriptions.foreach { case (method, consumer) =>
          unSubscribeToEvents(consumer)
            .andThen {
              case Success(_) =>
                self ! Unsubscribed(ws, method)
              case Failure(ex) =>
                self ! UnsubscriptionFailed(ws, method, ex.getMessage)
            }(context.dispatcher)
        }
      case _ =>
        self ! UnsubscriptionFailed(ws, method, s"Not subscribed to $method subscription")
    }
  }

  private def subscribeToEvents(ws: ServerWebSocket, method: WsMethod)(implicit
      ec: ExecutionContext
  ): Try[Subscription] = Try {
    vertx
      .eventBus()
      .consumer[String](
        method.name,
        new io.vertx.core.Handler[Message[String]] {
          override def handle(message: Message[String]): Unit = {
            if (!ws.isClosed) {
              val _ =
                ws.writeTextMessage(message.body()).asScala.andThen { case Failure(ex) =>
                  if (!ws.isClosed) {
                    self ! NotificationFailed(ws, method, ex.getMessage)
                  }
                }
            }
          }
        }
      )
  }

  private def unSubscribeToEvents(subscription: Subscription): Future[Unit] = {
    subscription
      .unregister()
      .asScala
      .mapTo[Unit]
  }

  private def unregister(id: SubscriberId)(implicit ec: ExecutionContext): Unit = {
    Future.sequence(
      subscribers.get(id).toList.flatMap { subscriptions =>
        subscriptions.map { case (_, consumer) =>
          unSubscribeToEvents(consumer) // TODO possible retry here
        }
      }
    ) onComplete { _ => self ! Unregistered(id) }
  }

  // TODO rewrite to JSON message
  private def respondAsyncAndIgnoreFailure(ws: ServerWebSocket, message: String)(implicit
      ec: ExecutionContext
  ): Unit = {
    val _ = ws
      .writeTextMessage(message)
      .asScala
      .andThen { case Failure(exception) =>
        log.warning(exception, s"Failed to write message: $message")
      }
  }

}
