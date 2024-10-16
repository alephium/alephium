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

import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable

import akka.actor.{ActorRef, ActorSystem, Props}
import com.typesafe.scalalogging.StrictLogging
import io.vertx.core.Vertx
import io.vertx.core.eventbus.{EventBus => VertxEventBus, Message, MessageConsumer}
import io.vertx.core.http.{HttpServer, HttpServerOptions, ServerWebSocket}

import org.alephium.api.ApiModelCodec
import org.alephium.api.model._
import org.alephium.app.WebSocketServer.WsEventType.Subscription
import org.alephium.flow.client.Node
import org.alephium.flow.handler.FlowHandler
import org.alephium.json.Json._
import org.alephium.protocol.config.NetworkConfig
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util.{ActorRefT, AVector, BaseActor, EventBus}

trait HttpServerLike {
  def underlying: HttpServer
}

final case class SimpleHttpServer(underlying: HttpServer) extends HttpServerLike

object SimpleHttpServer {
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def apply(httpOptions: HttpServerOptions = new HttpServerOptions): SimpleHttpServer =
    SimpleHttpServer(Vertx.vertx().createHttpServer(httpOptions))
}

final case class WebSocketServer(underlying: HttpServer, eventHandler: ActorRef)
    extends HttpServerLike

object WebSocketServer extends StrictLogging {
  private val tooManyRequestsCode  = 429
  private val currentWsConnections = new AtomicInteger(0)

  def apply(
      flowSystem: ActorSystem,
      node: Node,
      maxConnections: Int,
      httpOptions: HttpServerOptions
  )(implicit networkConfig: NetworkConfig): WebSocketServer = {
    val vertx = Vertx.vertx()
    val eventHandlerRef =
      EventHandler.getSubscribedEventHandlerRef(vertx.eventBus(), node.eventBus, flowSystem)
    val server = vertx.createHttpServer(httpOptions)
    server.webSocketHandler { webSocket =>
      if (currentWsConnections.get() >= maxConnections) {
        logger.warn(s"WebSocket connections reached max limit ${currentWsConnections.get()}")
        webSocket.reject(tooManyRequestsCode)
      } else if (webSocket.path().equals("/ws")) {
        webSocket.closeHandler { _ =>
          currentWsConnections.decrementAndGet()
          eventHandlerRef ! EventHandler.Unsubscribe(webSocket.textHandlerID())
        }

        // Receive subscription messages from the client
        webSocket.textMessageHandler { message =>
          WsEventType.parseSubscription(message) match {
            case Some(subscription) =>
              EventHandler.subscribeToEvents(vertx, webSocket, subscription)
            case None =>
              webSocket.reject()
          }
          ()
        }

        currentWsConnections.incrementAndGet()
        eventHandlerRef ! EventHandler.Subscribe(webSocket.textHandlerID())
      } else {
        webSocket.reject()
      }
    }
    WebSocketServer(server, eventHandlerRef)
  }

  sealed trait WsEventType {
    def name: String
  }

  object WsEventType {
    private val SubscribePrefix = "subscribe:"

    case object Block extends WsEventType { val name = "block" }
    case object Tx    extends WsEventType { val name = "tx"    }

    def fromString(name: String): Option[WsEventType] = name match {
      case Block.name => Some(Block)
      case Tx.name    => Some(Tx)
      case _          => None
    }

    final case class Subscription(eventType: WsEventType) {
      def message: String = s"$SubscribePrefix${eventType.name}"
    }

    def buildSubscribeMsg(eventType: WsEventType): String = s"$SubscribePrefix${eventType.name}"

    private def isSubscription(message: String): Boolean = message.startsWith(SubscribePrefix)

    def parseSubscription(message: String): Option[Subscription] = {
      if (isSubscription(message)) {
        message
          .split(":")
          .lastOption
          .map(_.trim)
          .flatMap(WsEventType.fromString)
          .map(Subscription)
      } else {
        None
      }
    }
  }

  object EventHandler extends ApiModelCodec {
    final case class Subscribe(clientId: String)
    final case class Unsubscribe(clientId: String)
    case object ListSubscribers

    // scalastyle:off null
    def subscribeToEvents(
        vertx: Vertx,
        ws: ServerWebSocket,
        subscription: Subscription
    ): MessageConsumer[String] = {
      vertx
        .eventBus()
        .consumer[String](
          subscription.eventType.name,
          new io.vertx.core.Handler[Message[String]] {
            override def handle(message: Message[String]): Unit = {
              if (!ws.isClosed) {
                val _ = ws.writeTextMessage(message.body(), null)
              }
            }
          }
        )
    }
    // scalastyle:off null

    def buildNotification(
        event: EventBus.Event
    )(implicit networkConfig: NetworkConfig): Either[String, Notification] = {
      event match {
        case FlowHandler.BlockNotify(block, height) =>
          BlockEntry.from(block, height).map { blockEntry =>
            Notification(WsEventType.Block.name, writeJs(blockEntry))
          }
      }
    }

    def getSubscribedEventHandlerRef(
        vertxEventBus: VertxEventBus,
        eventBusRef: ActorRefT[EventBus.Message],
        flowSystem: ActorSystem
    )(implicit
        networkConfig: NetworkConfig
    ): ActorRef = {
      val eventHandlerRef = flowSystem.actorOf(Props(new EventHandler(vertxEventBus)))
      eventBusRef.tell(EventBus.Subscribe, eventHandlerRef)
      eventHandlerRef
    }
  }

  class EventHandler(vertxEventBus: VertxEventBus)(implicit val networkConfig: NetworkConfig)
      extends BaseActor
      with ApiModelCodec {

    private val subscribers: mutable.HashSet[String] = mutable.HashSet.empty

    def receive: Receive = {
      case event: EventBus.Event =>
        EventHandler.buildNotification(event) match {
          case Right(notification) =>
            val _ = vertxEventBus.publish(notification.method, write(notification))
          case Left(error) =>
            log.error(error)
        }
      case EventHandler.Subscribe(subscriber) =>
        if (!subscribers.contains(subscriber)) { subscribers += subscriber }
      case EventHandler.Unsubscribe(subscriber) =>
        if (subscribers.contains(subscriber)) { subscribers -= subscriber }
      case EventHandler.ListSubscribers =>
        sender() ! AVector.unsafe(subscribers.toArray)
    }
  }
}
