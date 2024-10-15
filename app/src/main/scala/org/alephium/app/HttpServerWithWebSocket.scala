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
import io.vertx.core.Vertx
import io.vertx.core.eventbus.{EventBus => VertxEventBus, Message}
import io.vertx.core.http.{HttpServer, HttpServerOptions}

import org.alephium.api.ApiModelCodec
import org.alephium.api.model._
import org.alephium.flow.client.Node
import org.alephium.flow.handler.FlowHandler
import org.alephium.json.Json._
import org.alephium.protocol.config.NetworkConfig
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util.{AVector, BaseActor, EventBus}

trait HttpServerLike {
  def underlying: HttpServer
}

final case class SimpleHttpServer(underlying: HttpServer) extends HttpServerLike
object SimpleHttpServer {
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def apply(httpOptions: HttpServerOptions = new HttpServerOptions): SimpleHttpServer =
    SimpleHttpServer(Vertx.vertx().createHttpServer(httpOptions))
}

final case class HttpServerWithWebSocket(underlying: HttpServer, eventHandler: ActorRef)
    extends HttpServerLike
object HttpServerWithWebSocket {
  private val tooManyRequestsCode  = 429
  private val currentWsConnections = new AtomicInteger(0)

  // scalastyle:off method.length null
  def apply(
      flowSystem: ActorSystem,
      node: Node,
      maxConnections: Int,
      httpOptions: HttpServerOptions
  )(implicit
      networkConfig: NetworkConfig
  ): HttpServerWithWebSocket = {
    val vertx                  = Vertx.vertx()
    val eventHandler: ActorRef = flowSystem.actorOf(EventHandler.props(vertx.eventBus()))
    node.eventBus.tell(EventBus.Subscribe, eventHandler)
    val server = vertx.createHttpServer(httpOptions)
    server.webSocketHandler { webSocket =>
      if (currentWsConnections.get() >= maxConnections) {
        webSocket.reject(tooManyRequestsCode)
      } else if (webSocket.path().equals("/ws")) {
        webSocket.closeHandler { _ =>
          currentWsConnections.decrementAndGet()
          eventHandler ! EventHandler.Unsubscribe(webSocket.textHandlerID())
        }

        // Receive subscription messages from the client
        webSocket.textMessageHandler { message =>
          EventHandler.parseSubscriptionEventType(message) match {
            case Some(eventType) =>
              vertx
                .eventBus()
                .consumer[String](
                  eventType.name,
                  new io.vertx.core.Handler[Message[String]] {
                    override def handle(message: Message[String]): Unit = {
                      if (!webSocket.isClosed) {
                        val _ = webSocket.writeTextMessage(message.body(), null)
                      }
                    }
                  }
                )
            case None =>
              webSocket.reject()
          }
          ()
        }

        currentWsConnections.incrementAndGet()
        eventHandler ! EventHandler.Subscribe(webSocket.textHandlerID())
      } else {
        webSocket.reject()
      }
    }
    HttpServerWithWebSocket(server, eventHandler)
  }
  // scalastyle:on method.length null

  sealed abstract class WsEventType(val name: String)
  object WsEventType {
    case object Block extends WsEventType("block")
    case object Tx    extends WsEventType("tx")
    def fromString(name: String): Option[WsEventType] = name match {
      case Block.name => Some(Block)
      case Tx.name    => Some(Tx)
      case _          => None
    }
  }

  object EventHandler {
    final case class Subscribe(clientId: String)
    final case class Unsubscribe(clientId: String)
    case object ListSubscribers

    def parseSubscriptionEventType(message: String): Option[WsEventType] = {
      if (message.startsWith("subscribe:")) {
        message.split(":").lastOption.map(_.trim).flatMap(WsEventType.fromString)
      } else {
        None
      }
    }

    def props(vertxEventBus: VertxEventBus)(implicit networkConfig: NetworkConfig): Props = {
      Props(new EventHandler(vertxEventBus))
    }
  }

  class EventHandler(vertxEventBus: VertxEventBus)(implicit val networkConfig: NetworkConfig)
      extends BaseActor
      with ApiModelCodec {

    private val subscribers: mutable.HashSet[String] = mutable.HashSet.empty

    def receive: Receive = {
      case event: EventBus.Event => broadcast(event)
      case EventHandler.Subscribe(subscriber) =>
        if (!subscribers.contains(subscriber)) { subscribers += subscriber }
      case EventHandler.Unsubscribe(subscriber) =>
        if (subscribers.contains(subscriber)) { subscribers -= subscriber }
      case EventHandler.ListSubscribers =>
        sender() ! AVector.unsafe(subscribers.toArray)
    }

    private def broadcast(event: EventBus.Event): Unit = {
      event match {
        case FlowHandler.BlockNotify(block, height) =>
          BlockEntry.from(block, height) match {
            case Right(blockEntry) =>
              val params = writeJs(blockEntry)
              val notification = write(
                Notification(WsEventType.Block.name, params)
              )
              val _ = vertxEventBus.publish(WsEventType.Block.name, notification)
            case _ => // this should never happen
              log.error(s"Received invalid block $block")
          }
      }
    }
  }
}
