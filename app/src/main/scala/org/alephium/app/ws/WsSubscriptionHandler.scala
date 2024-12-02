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

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}

import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Vertx
import io.vertx.core.eventbus.{Message, MessageConsumer}

import org.alephium.app.ws.WsParams._
import org.alephium.app.ws.WsRequest.Correlation
import org.alephium.json.Json.write
import org.alephium.rpc.model.JsonRPC.{Error, Response}
import org.alephium.util.{AVector, BaseActor}

protected[ws] object WsSubscriptionHandler {

  sealed trait SubscriptionMsg
  sealed trait Command         extends SubscriptionMsg
  sealed trait CommandResponse extends SubscriptionMsg
  sealed trait Event           extends SubscriptionMsg

  final case class Connect(socket: ServerWsLike) extends Command
  final case object GetSubscriptions             extends Command

  final case class SubscriptionsResponse(
      subscriptions: AVector[(WsId, AVector[(WsSubscriptionId, MessageConsumer[String])])]
  ) extends CommandResponse

  final protected[ws] case class Subscribe(
      id: Correlation,
      ws: ServerWsLike,
      subscription: SubscribeParams
  ) extends Command
  final private case class Subscribed(
      id: Correlation,
      ws: ServerWsLike,
      subscriptionId: WsSubscriptionId,
      consumer: MessageConsumer[String]
  ) extends CommandResponse
  final private case class AlreadySubscribed(
      id: Correlation,
      ws: ServerWsLike,
      subscriptionId: WsSubscriptionId
  ) extends CommandResponse
  final private case class SubscriptionFailed(
      id: Correlation,
      ws: ServerWsLike,
      err: String
  ) extends CommandResponse

  final protected[ws] case class Unsubscribe(
      id: Correlation,
      ws: ServerWsLike,
      subscriptionId: WsSubscriptionId
  ) extends Command
  final private case class Unsubscribed(
      id: Correlation,
      ws: ServerWsLike,
      subscriptionId: WsSubscriptionId
  ) extends CommandResponse
  final private case class AlreadyUnSubscribed(
      id: Correlation,
      ws: ServerWsLike,
      subscriptionId: WsSubscriptionId
  ) extends CommandResponse
  final private case class UnsubscriptionFailed(
      id: Correlation,
      ws: ServerWsLike,
      err: String
  ) extends CommandResponse

  final private case class NotificationFailed(
      id: Correlation,
      ws: ServerWsLike,
      subscriptionId: WsSubscriptionId,
      msg: String
  ) extends Event

  final protected[ws] case class Unregister(id: WsId) extends Command
  final private case class Unregistered(id: WsId)     extends CommandResponse
}

protected[ws] class WsSubscriptionHandler(vertx: Vertx, maxConnections: Int) extends BaseActor {
  import org.alephium.app.ws.WsSubscriptionHandler._
  implicit private val ec: ExecutionContextExecutor = context.dispatcher

  // subscriber with empty subscriptions is connected but not subscribed to any events
  private val subscribers =
    mutable.Map.empty[WsId, AVector[(WsSubscriptionId, MessageConsumer[String])]]

  // scalastyle:off cyclomatic.complexity
  override def receive: Receive = {
    case Connect(ws) =>
      if (subscribers.size >= maxConnections) {
        log.warning(s"WebSocket connections reached max limit ${subscribers.size}")
        ws.reject(HttpResponseStatus.TOO_MANY_REQUESTS.code())
      } else {
        ws.closeHandler(() => self ! Unregister(ws.textHandlerID()))
        val _ = ws.textMessageHandler(msg => handleMessage(ws, msg))
      }
    case Subscribe(id, ws, subscription) =>
      subscribe(id, ws, subscription)
    case Unsubscribe(id, ws, subscriptionId) =>
      unSubscribe(id, ws, subscriptionId)
    case Subscribed(id, ws, subscriptionId, consumer) =>
      subscribers.updateWith(ws.textHandlerID()) {
        case Some(ss) if ss.exists(_._1 == subscriptionId) =>
          Some(ss)
        case Some(ss) =>
          Some(ss :+ (subscriptionId -> consumer))
        case None =>
          Some(AVector(subscriptionId -> consumer))
      }
      respondAsyncAndForget(ws, Response.successful(id, subscriptionId))
    case AlreadySubscribed(id, ws, subscriptionId) =>
      respondAsyncAndForget(
        ws,
        Response.failed(id, Error(WsError.AlreadySubscribed, subscriptionId))
      )
    case SubscriptionFailed(id, ws, reason) =>
      respondAsyncAndForget(ws, Response.failed(id, Error.server(reason)))
    case Unsubscribed(id, ws, subscriptionId) =>
      subscribers.updateWith(ws.textHandlerID())(_.map(_.filterNot(_._1 == subscriptionId)))
      respondAsyncAndForget(ws, Response.successful(id))
    case AlreadyUnSubscribed(id, ws, subscriptionId) =>
      respondAsyncAndForget(
        ws,
        Response.failed(id, Error(WsError.AlreadyUnsubscribed, subscriptionId))
      )
    case UnsubscriptionFailed(id, ws, reason) =>
      respondAsyncAndForget(ws, Response.failed(id, Error.server(reason)))
    case NotificationFailed(_, _, _, _) =>
    // TODO can we do something about failing notification to client which is not closed ?
    case Unregister(id) =>
      unregister(id)
    case Unregistered(id) =>
      val _ = subscribers.remove(id)
    case GetSubscriptions =>
      sender() ! SubscriptionsResponse(AVector.from(subscribers))
  }
  // scalastyle:on cyclomatic.complexity

  private def respondAsyncAndForget(ws: ServerWsLike, response: Response)(implicit
      ec: ExecutionContext
  ): Unit = {
    Try(write(response)) match {
      case Success(responseStr) =>
        val _ = ws
          .writeTextMessage(responseStr)
          .andThen { case Failure(exception) =>
            log.warning(exception, s"Failed to respond with: $responseStr")
          }
      case Failure(ex) =>
        log.warning(ex, s"Failed to serialize response: $response")
    }
  }

  private def handleMessage(ws: ServerWsLike, msg: String)(implicit
      ec: ExecutionContext
  ): Unit = {
    WsRequest.fromJsonString(msg) match {
      case Right(WsRequest(id, subscription: SubscribeParams)) =>
        val _ = self ! Subscribe(id, ws, subscription)
      case Right(WsRequest(_, FilteredSubscribeParams(_, _))) =>
      // TODO implement events for particular address etc.
      case Right(WsRequest(id, UnsubscribeParams(subscriptionId))) =>
        val _ = self ! Unsubscribe(id, ws, subscriptionId)
      case Left(error) => respondAsyncAndForget(ws, Response.failed(error))
    }
  }

  private def subscribe(
      id: Correlation,
      ws: ServerWsLike,
      subscription: SubscribeParams
  )(implicit
      ec: ExecutionContext
  ): Unit = {
    val subscriptionId = subscription.subscriptionId
    subscribers.get(ws.textHandlerID()) match {
      case Some(ss) if ss.exists(_._1 == subscriptionId) =>
        self ! AlreadySubscribed(id, ws, subscriptionId)
      case _ =>
        subscribeToEvents(id, ws, subscription.subscriptionId) match {
          case Success(consumer) =>
            self ! Subscribed(id, ws, subscriptionId, consumer)
          case Failure(ex) =>
            self ! SubscriptionFailed(id, ws, ex.getMessage)
        }
    }
  }

  private def unSubscribe(
      id: Correlation,
      ws: ServerWsLike,
      subscriptionId: WsSubscriptionId
  ): Unit = {
    subscribers.get(ws.textHandlerID()).map(_.filter(_._1 == subscriptionId)) match {
      case Some(subscriptions) if subscriptions.nonEmpty =>
        subscriptions.foreach { case (subscriptionId, consumer) =>
          unSubscribeToEvents(consumer)
            .andThen {
              case Success(_) =>
                self ! Unsubscribed(id, ws, subscriptionId)
              case Failure(ex) =>
                self ! UnsubscriptionFailed(id, ws, ex.getMessage)
            }(context.dispatcher)
        }
      case _ =>
        self ! AlreadyUnSubscribed(id, ws, subscriptionId)
    }
  }

  private def subscribeToEvents(
      id: Correlation,
      ws: ServerWsLike,
      subscriptionId: WsSubscriptionId
  )(implicit
      ec: ExecutionContext
  ): Try[MessageConsumer[String]] = Try {
    vertx
      .eventBus()
      .consumer[String](
        subscriptionId,
        new io.vertx.core.Handler[Message[String]] {
          override def handle(message: Message[String]): Unit = {
            if (!ws.isClosed) {
              val _ =
                ws.writeTextMessage(message.body()).andThen { case Failure(ex) =>
                  if (!ws.isClosed) {
                    self ! NotificationFailed(id, ws, subscriptionId, ex.getMessage)
                  }
                }
            }
          }
        }
      )
  }

  private def unSubscribeToEvents(subscription: MessageConsumer[String]): Future[Unit] = {
    import WsUtils._
    subscription
      .unregister()
      .asScala
      .mapTo[Unit]
  }

  private def unregister(id: WsId)(implicit ec: ExecutionContext): Unit = {
    Future.sequence(
      subscribers.get(id).toList.flatMap { subscriptions =>
        subscriptions.map { case (_, consumer) =>
          unSubscribeToEvents(consumer) // TODO possible retry here
        }
      }
    ) onComplete { _ => self ! Unregistered(id) }
  }

}
