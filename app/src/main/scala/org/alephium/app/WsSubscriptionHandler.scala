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

import org.alephium.app.WebSocketServer.{Correlation, Subscription, WebsocketId}
import org.alephium.app.WsProtocol.Code
import org.alephium.rpc.model.JsonRPC.{Error, Response}
import org.alephium.util.{AVector, BaseActor}

object WsSubscriptionHandler {

  sealed trait WsSubscription {
    def method: String
  }

  sealed trait SubscriptionMsg
  sealed trait Command         extends SubscriptionMsg
  sealed trait CommandResponse extends SubscriptionMsg
  sealed trait Event           extends SubscriptionMsg

  final case class ConnectAndSubscribe(socket: ServerWebSocket) extends Command
  final case object GetSubscriptions                            extends Command

  final private case class Subscribed(
      id: Correlation,
      ws: ServerWebSocket,
      params: WsParams,
      consumer: Subscription
  ) extends Event
  final private case class AlreadySubscribed(
      id: Correlation,
      ws: ServerWebSocket,
      params: WsParams
  ) extends Event
  final private case class SubscriptionFailed(
      id: Correlation,
      ws: ServerWebSocket,
      params: WsParams,
      err: String
  ) extends Event
  final private case class Unsubscribed(id: Correlation, ws: ServerWebSocket, params: WsParams)
      extends Event
  final private case class AlreadyUnSubscribed(
      id: Correlation,
      ws: ServerWebSocket,
      params: WsParams
  ) extends Event
  final private case class UnsubscriptionFailed(
      id: Correlation,
      ws: ServerWebSocket,
      params: WsParams,
      err: String
  ) extends Event
  final private case class NotificationFailed(
      id: Correlation,
      ws: ServerWebSocket,
      params: WsParams,
      msg: String
  ) extends Event
  final private case class Unregistered(id: WebsocketId) extends Event

  final case class SubscriptionsResponse(
      subscriptions: AVector[(WebsocketId, AVector[(WsParams, Subscription)])]
  ) extends CommandResponse
}

class WsSubscriptionHandler(vertx: Vertx, maxConnections: Int) extends BaseActor with WsUtils {
  import org.alephium.app.WsSubscriptionHandler._
  implicit private val ec: ExecutionContextExecutor = context.dispatcher

  // subscriber with empty subscriptions is connected but not subscribed to any events
  private val subscribers = mutable.Map.empty[WebsocketId, AVector[(WsParams, Subscription)]]

  // scalastyle:off method.length
  override def receive: Receive = {
    case ConnectAndSubscribe(ws) =>
      if (subscribers.size >= maxConnections) {
        log.warning(s"WebSocket connections reached max limit ${subscribers.size}")
        ws.reject(HttpResponseStatus.TOO_MANY_REQUESTS.code())
      } else {
        ws.closeHandler(_ => unregister(ws.textHandlerID()))
        val _ = ws.textMessageHandler(msg => handleMessage(ws, msg))
      }
    case Subscribed(id, ws, method, consumer) =>
      subscribers.updateWith(ws.textHandlerID()) {
        case Some(ss) if ss.exists(_._1 == method) =>
          Some(ss)
        case Some(ss) =>
          Some(ss :+ (method -> consumer))
        case None =>
          Some(AVector(method -> consumer))
      }
      respondAsyncAndForget(ws, Response.successful(id))
    case AlreadySubscribed(id, ws, params) =>
      respondAsyncAndForget(
        ws,
        Response.failed(id, Error(Code.AlreadySubscribed, s"Already subscribed to $params"))
      )
    case SubscriptionFailed(id, ws, _, reason) =>
      respondAsyncAndForget(ws, Response.failed(id, Error.server(reason)))
    case Unsubscribed(id, ws, params) =>
      subscribers.updateWith(ws.textHandlerID())(_.map(_.filterNot(_._1 == params)))
      respondAsyncAndForget(ws, Response.successful(id))
    case AlreadyUnSubscribed(id, ws, params) =>
      respondAsyncAndForget(
        ws,
        Response.failed(id, Error(Code.AlreadySubscribed, s"Already unsubscribed from $params"))
      )
    case UnsubscriptionFailed(id, ws, _, reason) =>
      respondAsyncAndForget(ws, Response.failed(id, Error.server(reason)))
    case NotificationFailed(_, _, _, _) =>
    // TODO can we do something about failing notification to client which is not closed ?
    case Unregistered(id) =>
      val _ = subscribers.remove(id)
    case GetSubscriptions =>
      sender() ! SubscriptionsResponse(AVector.from(subscribers))
  }
  // scalastyle:on method.length

  private def handleMessage(ws: ServerWebSocket, msg: String)(implicit
      ec: ExecutionContext
  ): Unit = {
    WsRequest.fromJsonString(msg) match {
      case Right(WsRequest(id, WsMethod.Subscribe, params)) =>
        val _ = subscribe(id, ws, params)
      case Right(WsRequest(id, WsMethod.Unsubscribe, params)) =>
        val _ = unSubscribe(id, ws, params)
      case Left(error) => respondAsyncAndForget(ws, Response.failed(error))
    }
  }

  private def subscribe(id: Correlation, ws: ServerWebSocket, params: WsParams)(implicit
      ec: ExecutionContext
  ): Unit = {
    subscribers.get(ws.textHandlerID()) match {
      case Some(ss) if ss.exists(_._1 == params) =>
        self ! AlreadySubscribed(id, ws, params)
      case _ =>
        subscribeToEvents(id, ws, params) match {
          case Success(consumer) =>
            self ! Subscribed(id, ws, params, consumer)
          case Failure(ex) =>
            self ! SubscriptionFailed(id, ws, params, ex.getMessage)
        }
    }
  }

  private def unSubscribe(id: Correlation, ws: ServerWebSocket, params: WsParams): Unit = {
    subscribers.get(ws.textHandlerID()).map(_.filter(_._1 == params)) match {
      case Some(subscriptions) if subscriptions.nonEmpty =>
        subscriptions.foreach { case (method, consumer) =>
          unSubscribeToEvents(consumer)
            .andThen {
              case Success(_) =>
                self ! Unsubscribed(id, ws, method)
              case Failure(ex) =>
                self ! UnsubscriptionFailed(id, ws, method, ex.getMessage)
            }(context.dispatcher)
        }
      case _ =>
        self ! UnsubscriptionFailed(id, ws, params, s"Not subscribed to $params subscription")
    }
  }

  private def subscribeToEvents(id: Correlation, ws: ServerWebSocket, params: WsParams)(implicit
      ec: ExecutionContext
  ): Try[Subscription] = Try {
    vertx
      .eventBus()
      .consumer[String](
        params.name,
        new io.vertx.core.Handler[Message[String]] {
          override def handle(message: Message[String]): Unit = {
            if (!ws.isClosed) {
              val _ =
                ws.writeTextMessage(message.body()).asScala.andThen { case Failure(ex) =>
                  if (!ws.isClosed) {
                    self ! NotificationFailed(id, ws, params, ex.getMessage)
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

  private def unregister(id: WebsocketId)(implicit ec: ExecutionContext): Unit = {
    Future.sequence(
      subscribers.get(id).toList.flatMap { subscriptions =>
        subscriptions.map { case (_, consumer) =>
          unSubscribeToEvents(consumer) // TODO possible retry here
        }
      }
    ) onComplete { _ => self ! Unregistered(id) }
  }

}
