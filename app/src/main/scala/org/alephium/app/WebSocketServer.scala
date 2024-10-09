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

import scala.collection.mutable

import akka.actor.{ActorRef, ActorSystem, Props}
import io.vertx.core.Vertx
import io.vertx.core.eventbus.{EventBus => VertxEventBus}
import io.vertx.core.http.{HttpServer, HttpServerOptions}

import org.alephium.api.ApiModelCodec
import org.alephium.api.model._
import org.alephium.flow.client.Node
import org.alephium.flow.handler.FlowHandler
import org.alephium.json.Json._
import org.alephium.protocol.config.NetworkConfig
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util.{AVector, BaseActor, EventBus}

final case class WebSocketServer(underlying: HttpServer, eventHandler: ActorRef)
    extends HttpServerLike
object WebSocketServer {

  def apply(flowSystem: ActorSystem, node: Node)(implicit
      networkConfig: NetworkConfig,
      apiConfig: ApiConfig
  ): WebSocketServer = {
    val vertx = Vertx.vertx()

    val eventHandler: ActorRef = flowSystem.actorOf(EventHandler.props(vertx.eventBus()))

    node.eventBus.tell(EventBus.Subscribe, eventHandler)

    val server = vertx
      .createHttpServer(
        new HttpServerOptions()
          .setMaxWebSocketFrameSize(1024 * 1024)
          .setRegisterWebSocketWriteHandlers(true)
      )

    server.webSocketHandler { webSocket =>
      webSocket.closeHandler(_ =>
        eventHandler ! EventHandler.Unsubscribe(webSocket.textHandlerID())
      )

      if (!webSocket.path().equals("/events")) {
        webSocket.reject();
      } else {
        eventHandler ! EventHandler.Subscribe(webSocket.textHandlerID())
      }
    }
    WebSocketServer(server, eventHandler)
  }

  object EventHandler {
    def props(
        vertxEventBus: VertxEventBus
    )(implicit networkConfig: NetworkConfig, apiConfig: ApiConfig): Props = {
      Props(new EventHandler(vertxEventBus))
    }

    final case class Subscribe(address: String)
    final case class Unsubscribe(address: String)
    case object ListSubscribers
  }
  class EventHandler(vertxEventBus: VertxEventBus)(implicit
      val networkConfig: NetworkConfig,
      apiConfig: ApiConfig
  ) extends BaseActor
      with ApiModelCodec {

    lazy val blockflowFetchMaxAge = apiConfig.blockflowFetchMaxAge

    private val subscribers: mutable.HashSet[String] = mutable.HashSet.empty

    def receive: Receive = {
      case event: EventBus.Event => handleEvent(event)
      case EventHandler.Subscribe(subscriber) =>
        if (!subscribers.contains(subscriber)) { subscribers += subscriber }
      case EventHandler.Unsubscribe(subscriber) =>
        if (subscribers.contains(subscriber)) { subscribers -= subscriber }
      case EventHandler.ListSubscribers =>
        sender() ! AVector.unsafe(subscribers.toArray)
    }

    private def handleEvent(event: EventBus.Event): Unit = {
      event match {
        case FlowHandler.BlockNotify(block, height) =>
          BlockEntry.from(block, height) match {
            case Right(blockEntry) =>
              val params       = writeJs(blockEntry)
              val notification = write(Notification("block_notify", params))
              subscribers.foreach(subscriber => vertxEventBus.send(subscriber, notification))
            case _ => // this should never happen
              log.error(s"Received invalid block $block")
          }
      }
    }
  }
}
