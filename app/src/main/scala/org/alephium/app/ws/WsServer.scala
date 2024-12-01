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

import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext

import akka.actor.{ActorSystem, Props}
import com.typesafe.scalalogging.StrictLogging
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Vertx
import io.vertx.core.http.{HttpServer, HttpServerOptions}

import org.alephium.app.HttpServerLike
import org.alephium.app.ws.WsEventHandler.getSubscribedEventHandler
import org.alephium.flow.client.Node
import org.alephium.protocol.config.NetworkConfig
import org.alephium.util.{ActorRefT, EventBus}

final case class WsServer(
    underlying: HttpServer,
    eventHandler: ActorRefT[EventBus.Message],
    subscribers: ActorRefT[WsSubscriptionHandler.SubscriptionMsg]
) extends HttpServerLike

object WsServer extends StrictLogging {
  implicit val wsExecutionContext: ExecutionContext =
    ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  def apply(system: ActorSystem, node: Node, maxConnections: Int, options: HttpServerOptions)(
      implicit networkConfig: NetworkConfig
  ): WsServer = {
    val vertx        = Vertx.vertx()
    val server       = vertx.createHttpServer(options)
    val eventHandler = getSubscribedEventHandler(vertx.eventBus(), node.eventBus, system)
    val subscriptionHandler =
      ActorRefT
        .build[WsSubscriptionHandler.SubscriptionMsg](
          system,
          Props(new WsSubscriptionHandler(vertx, maxConnections))
        )
    server.webSocketHandler { ws =>
      if (ws.path().equals("/ws")) {
        subscriptionHandler ! WsSubscriptionHandler.ConnectAndSubscribe(ws)
      } else {
        ws.reject(HttpResponseStatus.BAD_REQUEST.code())
      }
    }
    WsServer(server, eventHandler, subscriptionHandler)
  }
}
