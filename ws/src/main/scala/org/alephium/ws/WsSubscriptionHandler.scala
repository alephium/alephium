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

package org.alephium.ws

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

import akka.actor.{ActorSystem, Props}
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.{Message, MessageConsumer}
import io.vertx.core.http.{ServerWebSocketHandshake, WebSocketFrameType}

import org.alephium.api.model.ApiKey
import org.alephium.json.Json._
import org.alephium.protocol.config.GroupConfig
import org.alephium.rpc.model.JsonRPC
import org.alephium.rpc.model.JsonRPC.{Error, Response}
import org.alephium.util.{discard, ActorRefT, AVector, BaseActor}
import org.alephium.ws.WsParams._
import org.alephium.ws.WsSubscriptionsState.{ContractEventKey, SubscriptionOfConnection}
import org.alephium.ws.WsUtils._

object WsSubscriptionHandler {

  def apply(
      vertx: Vertx,
      system: ActorSystem,
      maxConnections: Int,
      apiKeys: AVector[ApiKey],
      maxSubscriptionsPerConnection: Int,
      maxContractEventAddresses: Int,
      pingFrequency: FiniteDuration
  )(implicit groupConfig: GroupConfig): ActorRefT[SubscriptionMsg] = {
    ActorRefT
      .build[WsSubscriptionHandler.SubscriptionMsg](
        system,
        Props(
          new WsSubscriptionHandler(
            vertx,
            maxConnections,
            apiKeys,
            maxSubscriptionsPerConnection,
            maxContractEventAddresses,
            pingFrequency
          )
        )
      )
  }

  sealed trait SubscriptionMsg
  sealed trait Event extends SubscriptionMsg

  final case class NotificationPublished(params: WsNotificationParams) extends Event
  final private case class NotificationFailed(
      id: WsCorrelationId,
      ws: ServerWsLike,
      subscriptionId: WsSubscriptionId,
      msg: String,
      error: String
  ) extends Event

  sealed trait Command         extends SubscriptionMsg
  sealed trait CommandResponse extends SubscriptionMsg

  final case class Handshake(handshake: ServerWebSocketHandshake) extends Command

  final case class Connect(socket: ServerWsLike) extends Command

  final case object GetSubscriptions extends Command

  final case class WsImmutableSubscriptions(
      connections: Map[WsId, AVector[(WsSubscriptionId, MessageConsumer[String])]],
      subscriptionsByContractKey: Map[ContractEventKey, AVector[SubscriptionOfConnection]],
      contractKeysBySubscription: Map[SubscriptionOfConnection, AVector[ContractEventKey]]
  ) extends CommandResponse

  final case class Subscribe(
      id: WsCorrelationId,
      ws: ServerWsLike,
      params: WsSubscriptionParams
  ) extends Command

  final case class Unsubscribe(
      id: WsCorrelationId,
      ws: ServerWsLike,
      subscriptionId: WsSubscriptionId
  ) extends Command

  final private case class RequestRejected(
      ws: ServerWsLike,
      response: JsonRPC.Response.Failure
  ) extends CommandResponse

  final case class Disconnect(id: WsId) extends Command
  final private case class PongReceived(id: WsId) extends Command
  final private case class ReleasePendingConnection(id: Long) extends Command

  private case object KeepAlive

  val ApiKeyHeader: String = "X-API-KEY"
  private val MaxMissedPongs: Int = 2
}

@SuppressWarnings(Array("org.wartremover.warts.ToString"))
class WsSubscriptionHandler(
    vertx: Vertx,
    maxConnections: Int,
    apiKeys: AVector[ApiKey],
    maxSubscriptionsPerConnection: Int,
    maxContractEventAddresses: Int,
    pingFrequency: FiniteDuration
)(implicit val groupConfig: GroupConfig)
    extends BaseActor
    with WsNotificationParamsCodec {

  import org.alephium.ws.WsSubscriptionHandler._
  implicit private val ec: ExecutionContextExecutor = context.dispatcher

  private val openedWsConnections = mutable.Map.empty[WsId, ServerWsLike]
  private val missedPongs         = mutable.Map.empty[WsId, Int]
  private val pendingWsConnections = mutable.LinkedHashSet.empty[Long]
  private var nextPendingConnectionId: Long = 0L

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

    // Close all active connections gracefully
    if (openedWsConnections.nonEmpty) {
      log.info(
        s"WebSocket handler stopping, closing ${openedWsConnections.size} active connections"
      )
      openedWsConnections.values.foreach { ws =>
        if (!ws.isClosed) {
          ws.writeTextMessage(
            """{"jsonrpc":"2.0","method":"server_shutdown","params":{}}"""
          ).recover { case ex: Throwable =>
            log.debug(s"Failed to send shutdown notification: ${ex.getMessage}")
            ()
          }(context.dispatcher)
          ()
        }
      }
    }
    ()
  }

  // scalastyle:off cyclomatic.complexity method.length
  override def receive: Receive = {
    case KeepAlive =>
      openedWsConnections.foreachEntry(keepAlive)
    case Handshake(hs) => handshake(hs)
    case Connect(ws)   => connect(ws)
    case Subscribe(id, ws, params) =>
      subscribe(id, ws, params)
    case Unsubscribe(id, ws, subscriptionId) =>
      unsubscribe(id, ws, subscriptionId)
    case RequestRejected(ws, response) =>
      respondAsyncAndForget(ws, response)
    case NotificationFailed(_, ws, _, msg, error) =>
      ws.writeTextMessage(msg).onComplete {
        case Success(_) =>
          log.warning(error, "Open ws connection recovered from notification writing error.")
        case Failure(ex) =>
          log.warning(ex, "Ws notification writing failed repeatedly, closing.")
          self ! Disconnect(ws.textHandlerID())
      }
    case Disconnect(id) =>
      val _         = openedWsConnections.remove(id)
      val _         = missedPongs.remove(id)
      val customers = subscriptionsState.getConsumers(id)
      subscriptionsState.removeAllSubscriptions(id)
      Future
        .sequence(customers.map(_.unregister().asScala).toSeq)
        .onComplete {
          case Success(_)  =>
          case Failure(ex) => log.warning(ex, "Unregistering consumer failed.")
        }
    case PongReceived(id) =>
      if (openedWsConnections.contains(id)) {
        missedPongs.update(id, 0)
      }
    case ReleasePendingConnection(id) =>
      pendingWsConnections.remove(id)
      ()
    case GetSubscriptions =>
      sender() ! WsImmutableSubscriptions(
        subscriptionsState.connections.toMap,
        subscriptionsState.subscriptionsByContractKey.toMap,
        subscriptionsState.contractKeysBySubscription.toMap
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
    vertx
      .eventBus()
      .publish(params.subscription.toHexString, asJsonRpcNotification(params).render())
    ()
  }

  private def keepAlive(wsId: WsId, ws: ServerWsLike): Unit = {
    if (ws.isClosed) {
      self ! Disconnect(wsId)
    } else {
      val missed = missedPongs.getOrElse(wsId, 0)
      if (missed >= MaxMissedPongs) {
        log.warning(s"WebSocket connection $wsId missed $missed pong responses, closing.")
        closeAndDisconnect(wsId, ws)
      } else {
        missedPongs.update(wsId, missed + 1)
        val _ = ws.writePing(Buffer.buffer("ping")).failed.foreach { ex =>
          log.debug(s"Failed to send ping to WebSocket connection $wsId: ${ex.getMessage}")
          closeAndDisconnect(wsId, ws)
        }
      }
    }
  }

  private def closeAndDisconnect(wsId: WsId, ws: ServerWsLike): Unit = {
    if (!ws.isClosed) {
      val _ = ws.close().recover { case ex: Throwable =>
        log.debug(s"Failed to close WebSocket connection $wsId: ${ex.getMessage}")
        ()
      }(context.dispatcher)
    }
    self ! Disconnect(wsId)
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

  private def handshake(handshake: ServerWebSocketHandshake): Unit = {
    discard {
      if (handshake.path().equals("/ws")) {
        if (!isAuthorized(handshake)) {
          handshake.reject(HttpResponseStatus.UNAUTHORIZED.code())
        } else {
          val connectionsCount = openedWsConnections.size + pendingWsConnections.size
          if (connectionsCount >= maxConnections) {
            log.warning(s"WebSocket connections reached max limit $connectionsCount")
            handshake.reject(HttpResponseStatus.SERVICE_UNAVAILABLE.code())
          } else {
            val pendingConnectionId = reservePendingConnection()
            val _ = handshake
              .accept()
              .asScala
              .failed
              .foreach(_ => self ! ReleasePendingConnection(pendingConnectionId))
            val _ = context.system.scheduler.scheduleOnce(
              pingFrequency,
              self,
              ReleasePendingConnection(pendingConnectionId)
            )
          }
        }
      } else {
        handshake.reject(HttpResponseStatus.BAD_REQUEST.code())
      }
    }
  }

  private def reservePendingConnection(): Long = {
    val id = nextPendingConnectionId
    nextPendingConnectionId += 1
    pendingWsConnections.add(id)
    id
  }

  private def releaseOldestPendingConnection(): Unit = {
    pendingWsConnections.headOption.foreach(pendingWsConnections.remove)
    ()
  }

  private def isAuthorized(handshake: ServerWebSocketHandshake): Boolean = {
    val apiKeyHeader = Option(handshake.headers().get(ApiKeyHeader))
    if (apiKeys.isEmpty) {
      apiKeyHeader.isEmpty
    } else {
      apiKeyHeader.exists(header => apiKeys.exists(_.value == header))
    }
  }

  private def connect(ws: ServerWsLike): Unit = {
    releaseOldestPendingConnection()
    missedPongs.update(ws.textHandlerID(), 0)
    ws.closeHandler(() => {
      log.debug(s"WebSocket connection closed: ${ws.textHandlerID()}")
      self ! Disconnect(ws.textHandlerID())
    })
    ws.frameHandler(frame =>
      if (frame.`type`() == WebSocketFrameType.PONG) self ! PongReceived(ws.textHandlerID())
    )
    ws.textMessageHandler(msg => handleMessage(ws, msg))
    openedWsConnections.put(ws.textHandlerID(), ws)
    log.debug(
      s"WebSocket connected: ${ws.textHandlerID()}, total connections: ${openedWsConnections.size}"
    )
    ()
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

  private def createNotificationHandler(
      id: WsCorrelationId,
      ws: ServerWsLike,
      subscriptionId: WsSubscriptionId
  )(implicit ec: ExecutionContext): io.vertx.core.Handler[Message[String]] = {
    new io.vertx.core.Handler[Message[String]] {
      override def handle(message: Message[String]): Unit = {
        if (!ws.isClosed) {
          ws.writeTextMessage(message.body()).onComplete {
            case Success(_) => // Success
            case Failure(ex) =>
              if (!ws.isClosed) {
                log.debug(
                  s"Notification failed but connection still open, retrying: ${ex.getMessage}"
                )
                self ! NotificationFailed(
                  id,
                  ws,
                  subscriptionId,
                  message.body(),
                  ex.getMessage
                )
              } else {
                log.debug(s"Notification failed due to closed connection, triggering cleanup")
                self ! Disconnect(ws.textHandlerID())
              }
          }
        }
      }
    }
  }

  private def validateSubscription(
      ws: ServerWsLike,
      subscriptionId: WsSubscriptionId
  ): Option[JsonRPC.Error] = {
    val subscriptions = subscriptionsState.getSubscriptions(ws.textHandlerID())
    if (subscriptions.contains(subscriptionId)) {
      Some(WsError.alreadySubscribed(subscriptionId))
    } else if (subscriptions.length >= maxSubscriptionsPerConnection) {
      Some(WsError.subscriptionLimitExceeded(maxSubscriptionsPerConnection))
    } else {
      None
    }
  }

  private def subscribe(
      id: WsCorrelationId,
      ws: ServerWsLike,
      params: WsSubscriptionParams
  ): Unit = {
    val subscriptionId = params.subscriptionId
    validateSubscription(ws, subscriptionId) match {
      case Some(error) =>
        respondAsyncAndForget(ws, Response.failed(id, error))
      case None =>
        Try {
          vertx
            .eventBus()
            .consumer[String](
              subscriptionId.toHexString,
              createNotificationHandler(id, ws, subscriptionId)
            )
        } match {
          case Success(consumer) =>
            subscriptionsState.addNewSubscription(ws.textHandlerID(), params, consumer)
            respondAsyncAndForget(ws, Response.successful(id, subscriptionId.toHexString))
          case Failure(ex) =>
            respondAsyncAndForget(ws, Response.failed(id, Error.server(ex.getMessage)))
        }
    }
  }

  private def unsubscribe(
      id: WsCorrelationId,
      ws: ServerWsLike,
      subscriptionId: WsSubscriptionId
  ): Unit = {
    subscriptionsState.getConsumer(ws.textHandlerID(), subscriptionId) match {
      case Some(consumer) =>
        subscriptionsState.removeSubscription(ws.textHandlerID(), subscriptionId)
        consumer
          .unregister()
          .asScala
          .onComplete {
            case Success(_) =>
              respondAsyncAndForget(ws, Response.successful(id))
            case Failure(ex) =>
              log.warning(ex, s"Failed to unregister consumer for subscription $subscriptionId")
              respondAsyncAndForget(ws, Response.successful(id))
          }(context.dispatcher)
      case _ =>
        respondAsyncAndForget(ws, Response.failed(id, WsError.alreadyUnSubscribed(subscriptionId)))
    }
  }
}
