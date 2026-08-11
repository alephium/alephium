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
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.immutable.ArraySeq
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration

import com.typesafe.scalalogging.StrictLogging
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.{ServerWebSocket, WebSocketFrame}
import org.apache.pekko.actor.ActorSystem

import org.alephium.api.model.ApiKey
import org.alephium.flow.client.Node
import org.alephium.http.HttpService
import org.alephium.protocol.config.{GroupConfig, NetworkConfig}
import org.alephium.util.{discard, AVector, Duration, Service}
import org.alephium.ws._
import org.alephium.ws.WsParams.WsId
import org.alephium.ws.WsUtils._

final class WsServer(
    httpService: HttpService,
    system: ActorSystem,
    node: Node,
    maxConnections: Int,
    apiKeys: AVector[ApiKey],
    maxRequestsPerSecond: Int,
    maxWriteQueueSize: Int,
    maxSubscriptionsPerConnection: Int,
    maxContractEventAddresses: Int,
    pingFrequency: Duration
)(implicit
    networkConfig: NetworkConfig,
    groupConfig: GroupConfig,
    val executionContext: ExecutionContext
) extends Service
    with StrictLogging {

  def vertx: Vertx = httpService.vertx

  private val connectionSlots = new AtomicInteger(0)

  lazy val subscriptionHandler =
    WsSubscriptionHandler.apply(
      vertx,
      system,
      connectionSlots,
      maxRequestsPerSecond,
      maxWriteQueueSize,
      maxSubscriptionsPerConnection,
      maxContractEventAddresses,
      FiniteDuration(pingFrequency.millis, TimeUnit.MILLISECONDS)
    )
  lazy val eventHandler =
    WsEventHandler.getSubscribedEventHandler(node.eventBus, subscriptionHandler, system)

  override def subServices: ArraySeq[Service] = ArraySeq(httpService)

  override protected def startSelfOnce(): Future[Unit] = {
    logger.info("Starting service: WsServer")

    // Initialize eventHandler to subscribe to event bus
    eventHandler

    httpService.httpServer
      .webSocketHandshakeHandler { handshake =>
        // accept()/reject() must be called on the Vert.x event loop thread (here), not via the
        // Akka actor, to ensure webSocketHandler fires with the correct event-loop context so
        // that ws.textMessageHandler() registration takes effect (required on macOS/kqueue).
        if (handshake.path() == "/ws") {
          val apiKeyHeader = Option(handshake.headers().get(WsSubscriptionHandler.ApiKeyHeader))
          val authorized =
            if (apiKeys.isEmpty) {
              apiKeyHeader.isEmpty
            } else {
              apiKeyHeader.exists(header => apiKeys.exists(_.value == header))
            }
          if (!authorized) {
            discard(handshake.reject(HttpResponseStatus.UNAUTHORIZED.code()))
          } else if (connectionSlots.getAndIncrement() >= maxConnections) {
            connectionSlots.decrementAndGet()
            discard(handshake.reject(HttpResponseStatus.SERVICE_UNAVAILABLE.code()))
          } else {
            // Slot claimed; release it if accept() fails before webSocketHandler fires
            handshake.accept().asScala.failed.foreach(_ => connectionSlots.decrementAndGet())
          }
        } else {
          discard(handshake.reject(HttpResponseStatus.BAD_REQUEST.code()))
        }
      }
      .webSocketHandler { ws =>
        val serverWs = ServerWs(ws)
        WsSubscriptionHandler.attachWebSocketHandlers(serverWs, maxWriteQueueSize) { message =>
          subscriptionHandler ! message
        }
        subscriptionHandler ! WsSubscriptionHandler.Connect.withAttachedHandlers(serverWs)
      }

    Future.unit
  }

  override protected def stopSelfOnce(): Future[Unit] = Future.unit
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

  def setWriteQueueMaxSize(maxSize: Int): ServerWs = {
    underlying.setWriteQueueMaxSize(maxSize)
    this
  }

  def writeQueueFull: Boolean =
    underlying.writeQueueFull()

  def writeTextMessage(msg: String): Future[Unit] =
    underlying.writeTextMessage(msg).asScala.mapTo[Unit]

  def writePing(data: Buffer): Future[Unit] =
    underlying.writePing(data).asScala.mapTo[Unit]

  def close(): Future[Unit] =
    underlying.close().asScala.map(_ => ())(ExecutionContext.parasitic)
}
