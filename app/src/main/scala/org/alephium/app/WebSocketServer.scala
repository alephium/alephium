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

import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext

import akka.actor.{ActorSystem, Props}
import com.typesafe.scalalogging.StrictLogging
import io.vertx.core.Vertx
import io.vertx.core.eventbus.{EventBus => VertxEventBus, MessageConsumer}
import io.vertx.core.http.{HttpServer, HttpServerOptions}

import org.alephium.api.ApiModelCodec
import org.alephium.api.model._
import org.alephium.app.WebSocketServer.WsEventHandler.getSubscribedEventHandler
import org.alephium.flow.client.Node
import org.alephium.flow.handler.AllHandlers.BlockNotify
import org.alephium.json.Json._
import org.alephium.protocol.config.NetworkConfig
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util.{ActorRefT, BaseActor, EventBus}

trait HttpServerLike {
  def underlying: HttpServer
}

final case class SimpleHttpServer(underlying: HttpServer) extends HttpServerLike

object SimpleHttpServer {
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def apply(httpOptions: HttpServerOptions = new HttpServerOptions): SimpleHttpServer =
    SimpleHttpServer(Vertx.vertx().createHttpServer(httpOptions))
}

final case class WebSocketServer(
    underlying: HttpServer,
    eventHandler: ActorRefT[EventBus.Message],
    subscribers: ActorRefT[WsSubscriptionHandler.SubscriptionMsg]
) extends HttpServerLike

object WebSocketServer extends StrictLogging {

  type SubscriberId = String
  type Subscriber   = MessageConsumer[String]

  implicit val wsExecutionContext: ExecutionContext =
    ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  def apply(system: ActorSystem, node: Node, maxConnections: Int, options: HttpServerOptions)(
      implicit networkConfig: NetworkConfig
  ): WebSocketServer = {
    val vertx        = Vertx.vertx()
    val server       = vertx.createHttpServer(options)
    val eventHandler = getSubscribedEventHandler(vertx.eventBus(), node.eventBus, system)
    val subscriptionHandler =
      ActorRefT
        .build[WsSubscriptionHandler.SubscriptionMsg](
          system,
          Props(new WsSubscriptionHandler(vertx, maxConnections))
        )
    server.webSocketHandler { webSocket =>
      subscriptionHandler ! WsSubscriptionHandler.ConnectAndSubscribe(webSocket)
    }
    WebSocketServer(server, eventHandler, subscriptionHandler)
  }

  object WsEventHandler extends ApiModelCodec {

    def buildNotification(
        event: EventBus.Event
    )(implicit networkConfig: NetworkConfig): Either[String, Notification] = {
      event match {
        case BlockNotify(block, height) =>
          BlockEntry.from(block, height).map { blockEntry =>
            Notification(WsMethod.Block.name, writeJs(blockEntry))
          }
      }
    }

    def getSubscribedEventHandler(
        vertxEventBus: VertxEventBus,
        eventBusRef: ActorRefT[EventBus.Message],
        system: ActorSystem
    )(implicit
        networkConfig: NetworkConfig
    ): ActorRefT[EventBus.Message] = {
      val eventHandlerRef =
        ActorRefT.build[EventBus.Message](system, Props(new WsEventHandler(vertxEventBus)))
      eventBusRef.tell(EventBus.Subscribe, eventHandlerRef.ref)
      eventHandlerRef
    }
  }

  class WsEventHandler(vertxEventBus: VertxEventBus)(implicit val networkConfig: NetworkConfig)
      extends BaseActor
      with ApiModelCodec {

    def receive: Receive = { case event: EventBus.Event =>
      WsEventHandler.buildNotification(event) match {
        case Right(notification) =>
          val _ = vertxEventBus.publish(notification.method, write(notification))
        case Left(error) =>
          log.error(error)
      }
    }
  }
}
