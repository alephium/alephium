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

import scala.collection.concurrent
import scala.concurrent.{Future => ScalaFuture, Promise}
import scala.concurrent.ExecutionContext
import scala.util.Failure

import akka.actor.{ActorRef, ActorSystem, Props}
import com.typesafe.scalalogging.StrictLogging
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.{Future => VertxFuture}
import io.vertx.core.Vertx
import io.vertx.core.eventbus.{EventBus => VertxEventBus, Message, MessageConsumer}
import io.vertx.core.http.{HttpServer, HttpServerOptions, ServerWebSocket}

import org.alephium.api.ApiModelCodec
import org.alephium.api.model._
import org.alephium.app.WebSocketServer.{Subscriber, SubscriberId, WsMethod}
import org.alephium.app.WebSocketServer.EventHandler.{
  getSubscribedEventHandlerRef,
  subscribeToEvents,
  unSubscribeToEvents
}
import org.alephium.flow.client.Node
import org.alephium.flow.handler.AllHandlers.BlockNotify
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

sealed trait WsSupport {
  def toScalaFut[T](vertxFuture: VertxFuture[T]): ScalaFuture[T] = {
    val promise = Promise[T]()
    vertxFuture.onComplete {
      case handler if handler.succeeded() =>
        promise.success(handler.result())
      case handler if handler.failed() =>
        promise.failure(handler.cause())
    }
    promise.future
  }
}

final case class WebSocketServer(
    underlying: HttpServer,
    eventHandler: ActorRef,
    subscribers: concurrent.TrieMap[SubscriberId, AVector[(WsMethod, Subscriber)]]
) extends HttpServerLike

object WebSocketServer extends WsSupport with StrictLogging {
  type SubscriberId = String
  type Subscriber   = MessageConsumer[String]

  implicit val wsExecutionContext: ExecutionContext =
    ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  def apply(
      flowSystem: ActorSystem,
      node: Node,
      maxConnections: Int,
      httpOptions: HttpServerOptions
  )(implicit networkConfig: NetworkConfig): WebSocketServer = {
    val vertx           = Vertx.vertx()
    val eventHandlerRef = getSubscribedEventHandlerRef(vertx.eventBus(), node.eventBus, flowSystem)
    val subscribers     = concurrent.TrieMap.empty[SubscriberId, AVector[(WsMethod, Subscriber)]]
    val server          = vertx.createHttpServer(httpOptions)
    server.webSocketHandler { webSocket =>
      if (subscribers.size >= maxConnections) {
        logger.warn(s"WebSocket connections reached max limit ${subscribers.size}")
        webSocket.reject(HttpResponseStatus.TOO_MANY_REQUESTS.code())
      } else if (webSocket.path().equals("/ws")) {
        webSocket.closeHandler { _ =>
          subscribers.get(webSocket.textHandlerID()).foreach { consumers =>
            consumers.foreach { case (_, consumer) => unSubscribeToEvents(consumer) }
          }
          val _ = subscribers.remove(webSocket.textHandlerID())
        }
        val _ = webSocket.textMessageHandler { message =>
          WsEvent.parseEvent(message) match {
            case Some(WsEvent(WsCommand.Subscribe, method)) =>
              val consumer = subscribeToEvents(vertx, webSocket, method)
              val _ = subscribers.updateWith(webSocket.textHandlerID()) {
                case Some(ss) if ss.exists(_._1 == method) => Some(ss)
                case Some(ss)                              => Some(ss :+ (method -> consumer))
                case None                                  => Some(AVector(method -> consumer))
              }
            case Some(WsEvent(WsCommand.Unsubscribe, method)) =>
              val _ = subscribers.updateWith(webSocket.textHandlerID()) {
                case Some(ss) =>
                  val (toRemove, toKeep) = ss.partition(_._1 == method)
                  toRemove.foreach { case (_, c) => unSubscribeToEvents(c) }
                  Some(toKeep)
                case None => None
              }
            case None =>
              val _ = toScalaFut(webSocket.writeTextMessage(s"Unsupported message : $message"))
                .andThen { case Failure(exception) =>
                  logger.warn(s"Failed to write message: $message", exception)
                }
          }
        }
      } else {
        webSocket.reject(HttpResponseStatus.BAD_REQUEST.code())
      }
    }
    WebSocketServer(server, eventHandlerRef, subscribers)
  }

  sealed trait WsMethod {
    def index: Int
    def name: String
  }
  object WsMethod {
    case object Block extends WsMethod { val name = "block"; val index = 0 }
    case object Tx    extends WsMethod { val name = "tx"; val index = 1    }
    def fromString(name: String): Option[WsMethod] = name match {
      case Block.name => Some(Block)
      case Tx.name    => Some(Tx)
      case _          => None
    }
  }
  sealed trait WsCommand {
    def name: String
  }
  object WsCommand {
    case object Subscribe   extends WsCommand { val name = "subscribe"   }
    case object Unsubscribe extends WsCommand { val name = "unsubscribe" }
    def fromString(name: String): Option[WsCommand] = name match {
      case Subscribe.name   => Some(Subscribe)
      case Unsubscribe.name => Some(Unsubscribe)
      case _                => None
    }

  }
  final case class WsEvent(command: WsCommand, method: WsMethod) {
    override def toString: String = s"${command.name}:${method.name}"
  }
  object WsEvent {
    def parseEvent(event: String): Option[WsEvent] = {
      event.split(":").toList match {
        case commandStr :: methodStr :: Nil =>
          for {
            command <- WsCommand.fromString(commandStr)
            method  <- WsMethod.fromString(methodStr)
          } yield WsEvent(command, method)
        case _ => None
      }
    }
  }

  sealed trait WsSubscription {
    def method: String
  }

  object EventHandler extends WsSupport with ApiModelCodec {

    def subscribeToEvents(
        vertx: Vertx,
        ws: ServerWebSocket,
        method: WsMethod
    ): MessageConsumer[String] = {
      vertx
        .eventBus()
        .consumer[String](
          method.name,
          new io.vertx.core.Handler[Message[String]] {
            override def handle(message: Message[String]): Unit = {
              if (!ws.isClosed) {
                val _ = toScalaFut(ws.writeTextMessage(message.body()))
                  .andThen { case Failure(exception) =>
                    logger.warn(s"Failed to write message: $message", exception)
                  }
              }
            }
          }
        )
    }

    def unSubscribeToEvents(consumer: MessageConsumer[String]): Unit = {
      val _ = toScalaFut(consumer.unregister())
        .andThen { case Failure(exception) =>
          logger.warn(s"Failed to unsubscribe event consumer", exception)
        }
    }

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

    def receive: Receive = { case event: EventBus.Event =>
      EventHandler.buildNotification(event) match {
        case Right(notification) =>
          val _ = vertxEventBus.publish(notification.method, write(notification))
        case Left(error) =>
          log.error(error)
      }
    }
  }
}
