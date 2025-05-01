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

import java.util.concurrent.TimeUnit

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

import akka.actor.ActorSystem
import com.typesafe.scalalogging.StrictLogging
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.{HttpServer, HttpServerOptions, ServerWebSocket, WebSocketFrame}

import org.alephium.flow.client.Node
import org.alephium.http.HttpServerLike
import org.alephium.protocol.config.NetworkConfig
import org.alephium.util.{ActorRefT, Duration, EventBus}
import org.alephium.ws._
import org.alephium.ws.WsParams.WsId

final case class WsServer(
    httpServer: HttpServer,
    eventHandler: ActorRefT[EventBus.Message],
    subscriptionHandler: ActorRefT[WsSubscriptionHandler.SubscriptionMsg]
) extends HttpServerLike

object WsServer extends StrictLogging {

  // scalastyle:off parameter.number
  def apply(
      system: ActorSystem,
      node: Node,
      maxConnections: Int,
      maxSubscriptionsPerConnection: Int,
      maxContractEventAddresses: Int,
      pingFrequency: Duration,
      options: HttpServerOptions
  )(implicit
      networkConfig: NetworkConfig
  ): WsServer = {
    val vertx  = Vertx.vertx()
    val server = vertx.createHttpServer(options)

    val subscriptionHandler =
      WsSubscriptionHandler.apply(
        vertx,
        system,
        maxConnections,
        maxSubscriptionsPerConnection,
        maxContractEventAddresses,
        FiniteDuration(pingFrequency.millis, TimeUnit.MILLISECONDS)
      )
    val eventHandler =
      WsEventHandler.getSubscribedEventHandler(node.eventBus, subscriptionHandler, system)
    server
      .webSocketHandshakeHandler { handshake =>
        subscriptionHandler ! WsSubscriptionHandler.Handshake(handshake)
      }
      .webSocketHandler { ws =>
        subscriptionHandler ! WsSubscriptionHandler.Connect(ServerWs(ws))
      }
    WsServer(server, eventHandler, subscriptionHandler)
  }
  // scalastyle:on parameter.number
}

final case class ServerWs(underlying: ServerWebSocket) extends ServerWsLike {
  import org.alephium.ws.WsUtils._
  def textHandlerID(): WsId = underlying.textHandlerID()
  def isClosed: Boolean     = underlying.isClosed
  def closeHandler(handler: () => Unit): ServerWs = {
    underlying.closeHandler(_ => handler())
    this
  }

  def textMessageHandler(handler: String => Unit): ServerWs = {
    underlying.textMessageHandler((msg: String) => handler(msg))
    this
  }

  def frameHandler(handler: WebSocketFrame => Unit): ServerWs = {
    underlying.frameHandler((msg: WebSocketFrame) => handler(msg))
    this
  }

  def writeTextMessage(msg: String): Future[Unit] =
    underlying.writeTextMessage(msg).asScala.mapTo[Unit]

  def writePing(data: Buffer): Future[Unit] =
    underlying.writePing(data).asScala.mapTo[Unit]
}
