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

import scala.concurrent.{ExecutionContext, Future}

import akka.actor.ActorSystem
import com.typesafe.scalalogging.StrictLogging
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Vertx
import io.vertx.core.http.{HttpServer, HttpServerOptions, ServerWebSocket}

import org.alephium.app.HttpServerLike
import org.alephium.app.ws.WsEventHandler
import org.alephium.app.ws.WsParams.WsId
import org.alephium.flow.client.Node
import org.alephium.protocol.config.NetworkConfig
import org.alephium.util.{ActorRefT, EventBus}

final case class WsServer(
    httpServer: HttpServer,
    eventHandler: ActorRefT[EventBus.Message],
    subscriptionHandler: ActorRefT[WsSubscriptionHandler.SubscriptionMsg]
) extends HttpServerLike

object WsServer extends StrictLogging {
  implicit val wsExecutionContext: ExecutionContext =
    ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  def apply(system: ActorSystem, node: Node, maxConnections: Int, options: HttpServerOptions)(
      implicit networkConfig: NetworkConfig
  ): WsServer = {
    val vertx               = Vertx.vertx()
    val server              = vertx.createHttpServer(options)
    val subscriptionHandler = WsSubscriptionHandler.apply(vertx, system, maxConnections)
    val eventHandler =
      WsEventHandler.getSubscribedEventHandler(node.eventBus, subscriptionHandler, system)
    server.webSocketHandler { ws =>
      if (ws.path().equals("/ws")) {
        subscriptionHandler ! WsSubscriptionHandler.Connect(ServerWs(ws))
      } else {
        ws.reject(HttpResponseStatus.BAD_REQUEST.code())
      }
    }
    WsServer(server, eventHandler, subscriptionHandler)
  }
}

trait ServerWsLike {
  def textHandlerID(): WsId
  def isClosed: Boolean
  def reject(statusCode: Int): Unit
  def closeHandler(handler: () => Unit): ServerWsLike
  def textMessageHandler(handler: String => Unit): ServerWsLike
  def writeTextMessage(msg: String): Future[Unit]
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

  def writeTextMessage(msg: String): Future[Unit] =
    underlying.writeTextMessage(msg).asScala.mapTo[Unit]
}
