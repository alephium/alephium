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
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

import akka.actor.{ActorSystem, Props}
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.{Message, MessageConsumer}

import org.alephium.app.ws.WsParams._
import org.alephium.app.ws.WsRequest.Correlation
import org.alephium.app.ws.WsUtils._
import org.alephium.json.Json.write
import org.alephium.rpc.model.JsonRPC
import org.alephium.rpc.model.JsonRPC.{Error, Response}
import org.alephium.util.{ActorRefT, AVector, BaseActor}

protected[ws] object WsSubscriptionHandler {

  def apply(
      vertx: Vertx,
      system: ActorSystem,
      maxConnections: Int,
      maxSubscriptionsPerConnection: Int,
      maxContractEventAddresses: Int,
      pingFrequency: FiniteDuration
  ): ActorRefT[SubscriptionMsg] = {
    ActorRefT
      .build[WsSubscriptionHandler.SubscriptionMsg](
        system,
        Props(
          new WsSubscriptionHandler(
            vertx,
            maxConnections,
            maxSubscriptionsPerConnection,
            maxContractEventAddresses,
            pingFrequency
          )
        )
      )
  }

  sealed trait SubscriptionMsg
  sealed trait Event extends SubscriptionMsg

  final protected[ws] case class NotificationPublished(params: WsNotificationParams) extends Event
  final private case class NotificationFailed(
      id: Correlation,
      ws: ServerWsLike,
      subscriptionId: WsSubscriptionId,
      msg: String,
      error: String
  ) extends Event

  sealed trait Command         extends SubscriptionMsg
  sealed trait CommandResponse extends SubscriptionMsg

  final case class Connect(socket: ServerWsLike) extends Command
  final case object GetSubscriptions             extends Command

  final case class WsImmutableSubscriptions(
      subscriptions: Map[WsId, AVector[(WsSubscriptionId, MessageConsumer[String])]],
      subscriptionsByAddress: Map[AddressWithIndex, AVector[SubscriptionOfConnection]],
      addressesBySubscriptionId: Map[SubscriptionOfConnection, AVector[AddressWithIndex]]
  ) extends CommandResponse

  final protected[ws] case class Subscribe(
      id: Correlation,
      ws: ServerWsLike,
      params: WsSubscriptionParams
  ) extends Command
  final private case class Subscribed(
      id: Correlation,
      ws: ServerWsLike,
      params: WsSubscriptionParams,
      consumer: MessageConsumer[String]
  ) extends CommandResponse
  final private case class RequestRejected(
      ws: ServerWsLike,
      response: JsonRPC.Response.Failure
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

  final protected[ws] case class Disconnect(id: WsId) extends Command

  private case object KeepAlive

  final protected[ws] case class AddressWithIndex(address: String, eventIndex: WsEventIndex)
  final protected[ws] case class SubscriptionOfConnection(
      wsId: WsId,
      subscriptionId: WsSubscriptionId
  )
}

protected[ws] class WsSubscriptionHandler(
    vertx: Vertx,
    maxConnections: Int,
    maxSubscriptionsPerConnection: Int,
    maxContractEventAddresses: Int,
    pingFrequency: FiniteDuration
) extends BaseActor {
  import org.alephium.app.ws.WsSubscriptionHandler._
  implicit private val ec: ExecutionContextExecutor = context.dispatcher

  private val openedWebSockets = mutable.Map.empty[WsId, ServerWsLike]

  private val subscriptionsState = WsSubscriptionsState.empty[MessageConsumer[String]]()

  private val pingScheduler =
    context.system.scheduler.scheduleWithFixedDelay(
      pingFrequency,
      pingFrequency,
      self,
      KeepAlive
    )

  override def postStop(): Unit = {
    pingScheduler.cancel()
    ()
  }

  // scalastyle:off cyclomatic.complexity method.length
  override def receive: Receive = {
    case KeepAlive =>
      openedWebSockets.foreachEntry { case (wsId, ws) =>
        if (ws.isClosed) self ! Disconnect(wsId) else ws.writePing(Buffer.buffer("ping"))
      }
    case Connect(ws) =>
      connect(ws)
    case Subscribe(id, ws, params) =>
      subscribe(id, ws, params)
    case Unsubscribe(id, ws, subscriptionId) =>
      unsubscribe(id, ws, subscriptionId)
    case Subscribed(id, ws, params, consumer) =>
      subscriptionsState.addNewSubscription(ws.textHandlerID(), params, consumer)
      respondAsyncAndForget(ws, Response.successful(id, params.subscriptionId.toHexString))
    case RequestRejected(ws, response) =>
      respondAsyncAndForget(ws, response)
    case Unsubscribed(id, ws, subscriptionId) =>
      subscriptionsState.removeSubscription(ws.textHandlerID(), subscriptionId)
      respondAsyncAndForget(ws, Response.successful(id))
    case NotificationFailed(_, ws, _, msg, error) =>
      ws.writeTextMessage(msg).onComplete {
        case Success(_) =>
          log.warning(error, "Open ws connection recovered from notification writing error.")
        case Failure(ex) =>
          log.warning(ex, "Ws notification writing failed repeatedly, closing.")
          self ! Disconnect(ws.textHandlerID())
      }
    case Disconnect(id) =>
      val _ = openedWebSockets.remove(id)
      subscriptionsState.removeAllSubscriptions(id)
      Future
        .sequence(subscriptionsState.getConsumers(id).map(_.unregister().asScala).toSeq)
        .onComplete {
          case Success(_)  =>
          case Failure(ex) => log.warning(ex, "Unregistering consumer failed.")
        }
    case GetSubscriptions =>
      sender() ! WsImmutableSubscriptions(
        subscriptionsState.connections.toMap,
        subscriptionsState.contractSubscriptionMappings.toMap,
        subscriptionsState.contractAddressMappings.toMap
      )
    case NotificationPublished(params: WsTxNotificationParams) =>
      publishNotification(params)
    case NotificationPublished(params: WsBlockNotificationParams) =>
      val _ = publishNotification(params)
      params.result.getContractEvents.foreach { contractEvent =>
        subscriptionsState
          .getUniqueSubscriptionIds(contractEvent.contractAddress, contractEvent.eventIndex)
          .map(WsContractEventNotificationParams(_, contractEvent))
          .foreach(publishNotification)
      }
  }
  // scalastyle:on cyclomatic.complexity method.length

  private def publishNotification(params: WsNotificationParams): Unit = {
    vertx.eventBus().publish(params.subscription.toHexString, params.asJsonRpcNotification.render())
    ()
  }

  private def respondAsyncAndForget(ws: ServerWsLike, response: Response)(implicit
      ec: ExecutionContext
  ): Unit = {
    Try(write(response)) match {
      case Success(responseStr) =>
        if (!ws.isClosed) {
          val _ = ws
            .writeTextMessage(responseStr)
            .andThen { case Failure(exception) =>
              log.warning(exception, s"Failed to respond with: $responseStr")
            }
        }
      case Failure(ex) =>
        log.warning(ex, s"Failed to serialize response: $response")
    }
  }

  private def connect(ws: ServerWsLike): Unit = {
    val connectionsCount = subscriptionsState.connections.size
    if (connectionsCount >= maxConnections) {
      log.warning(s"WebSocket connections reached max limit $connectionsCount")
      ws.reject(HttpResponseStatus.TOO_MANY_REQUESTS.code())
    } else {
      ws.frameHandler { frame =>
        if (frame.isPing) {
          ws.writePong(Buffer.buffer("pong")).onComplete {
            case Success(_) =>
            case Failure(ex) =>
              log.error(ex, "Websocket keep-alive failure")
          }
        }
      }
      ws.closeHandler(() => self ! Disconnect(ws.textHandlerID()))
      val _ = ws.textMessageHandler(msg => handleMessage(ws, msg))
      val _ = openedWebSockets.put(ws.textHandlerID(), ws)
    }
  }

  private def handleMessage(ws: ServerWsLike, msg: String): Unit = {
    WsRequest.fromJsonString(msg, maxContractEventAddresses) match {
      case Right(WsRequest(id, UnsubscribeParams(subscriptionId))) =>
        val _ = self ! Unsubscribe(id, ws, subscriptionId)
      case Right(WsRequest(id, params: WsSubscriptionParams)) =>
        val _ = self ! Subscribe(id, ws, params)
      case Left(failure) =>
        val _ = self ! RequestRejected(ws, failure)
    }
  }

  private def subscribe(id: Correlation, ws: ServerWsLike, params: WsSubscriptionParams)(implicit
      ec: ExecutionContext
  ): Unit = {
    val subscriptionId = params.subscriptionId

    def registerConsumer: Try[MessageConsumer[String]] = Try {
      vertx
        .eventBus()
        .consumer[String](
          subscriptionId.toHexString,
          new io.vertx.core.Handler[Message[String]] {
            override def handle(message: Message[String]): Unit = {
              if (!ws.isClosed) {
                ws.writeTextMessage(message.body()).onComplete {
                  case Success(_) =>
                  case Failure(ex) =>
                    if (!ws.isClosed) {
                      self ! NotificationFailed(
                        id,
                        ws,
                        subscriptionId,
                        message.body(),
                        ex.getMessage
                      )
                    }
                }
              }
            }
          }
        )
    }

    subscriptionsState.getSubscriptions(ws.textHandlerID()) match {
      case subscriptions if subscriptions.contains(subscriptionId) =>
        self ! RequestRejected(ws, Response.failed(id, WsError.alreadySubscribed(subscriptionId)))
      case subscriptions if subscriptions.length >= maxSubscriptionsPerConnection =>
        self ! RequestRejected(
          ws,
          Response.failed(id, WsError.subscriptionLimitExceeded(maxSubscriptionsPerConnection))
        )
      case _ =>
        registerConsumer match {
          case Success(consumer) =>
            self ! Subscribed(id, ws, params, consumer)
          case Failure(ex) =>
            self ! RequestRejected(ws, Response.failed(id, Error.server(ex.getMessage)))
        }
    }
  }

  private def unsubscribe(
      id: Correlation,
      ws: ServerWsLike,
      subscriptionId: WsSubscriptionId
  ): Unit = {
    subscriptionsState.getConsumer(ws.textHandlerID(), subscriptionId) match {
      case Some(consumer) =>
        consumer
          .unregister()
          .asScala
          .onComplete {
            case Success(_) =>
              self ! Unsubscribed(id, ws, subscriptionId)
            case Failure(ex) =>
              self ! RequestRejected(ws, Response.failed(id, Error.server(ex.getMessage)))
          }(context.dispatcher)
      case _ =>
        self ! RequestRejected(ws, Response.failed(id, WsError.alreadyUnSubscribed(subscriptionId)))
    }
  }
}
