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

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success}

import akka.actor.ActorSystem
import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.{HttpServer, HttpServerOptions, ServerWebSocket, WebSocketFrame}

import org.alephium.app.HttpServerLike
import org.alephium.app.ws.WsParams.WsId
import org.alephium.app.ws.WsSubscriptionHandler.ConnectResult
import org.alephium.app.ws.WsUtils._
import org.alephium.flow.client.Node
import org.alephium.protocol.config.NetworkConfig
import org.alephium.util.{ActorRefT, Duration, EventBus}

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
      executionContext: ExecutionContext,
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
    server.webSocketHandler { ws =>
      if (ws.path().equals("/ws")) {
        // async rejection is not supported, using handshaking instead https://github.com/eclipse-vertx/vert.x/issues/2858
        ws.setHandshake(
          subscriptionHandler
            .ask(WsSubscriptionHandler.Connect(ServerWs(ws)))(Timeout(100.millis))
            .mapTo[ConnectResult]
            .map(result => Integer.valueOf(result.status.code()))
            .asVertx
        ).asScala
          .onComplete {
            case Success(_) =>
            case Failure(ex) =>
              logger.warn("Handshaking ws connection failed", ex)
          }
      } else {
        ws.reject(HttpResponseStatus.BAD_REQUEST.code())
      }
    }
    WsServer(server, eventHandler, subscriptionHandler)
  }
  // scalastyle:on parameter.number
}

trait ServerWsLike {
  def textHandlerID(): WsId
  def isClosed: Boolean
  def reject(statusCode: Int): Unit
  def closeHandler(handler: () => Unit): ServerWsLike
  def textMessageHandler(handler: String => Unit): ServerWsLike
  def frameHandler(handler: WebSocketFrame => Unit): ServerWsLike
  def writeTextMessage(msg: String): Future[Unit]
  def writePong(data: Buffer): Future[Unit]
  def writePing(data: Buffer): Future[Unit]
}

final case class ServerWs(underlying: ServerWebSocket) extends ServerWsLike {
  import org.alephium.app.ws.WsUtils._
  def textHandlerID(): WsId         = underlying.textHandlerID()
  def isClosed: Boolean             = underlying.isClosed
  def reject(statusCode: Int): Unit = underlying.reject(statusCode)
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

  def writePong(data: Buffer): Future[Unit] =
    underlying.writePong(data).asScala.mapTo[Unit]

  def writePing(data: Buffer): Future[Unit] =
    underlying.writePing(data).asScala.mapTo[Unit]
}
