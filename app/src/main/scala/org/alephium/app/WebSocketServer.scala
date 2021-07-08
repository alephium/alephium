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

import scala.collection.immutable.ArraySeq
import scala.collection.mutable
import scala.concurrent._

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging
import io.vertx.core.Vertx
import io.vertx.core.eventbus.{EventBus => VertxEventBus}
import io.vertx.core.http.HttpServer
import sttp.tapir.server.vertx.VertxFutureServerInterpreter._

import org.alephium.api.ApiModelCodec
import org.alephium.api.model._
import org.alephium.flow.client.Node
import org.alephium.flow.handler.FlowHandler
import org.alephium.flow.handler.FlowHandler.BlockNotify
import org.alephium.json.Json._
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.NetworkType
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util.{AVector, BaseActor, EventBus, Service}

class WebSocketServer(node: Node, wsPort: Int)(implicit
    val system: ActorSystem,
    val apiConfig: ApiConfig,
    val executionContext: ExecutionContext
) extends ApiModelCodec
    with StrictLogging
    with Service {
  import WebSocketServer._

  implicit val groupConfig: GroupConfig = node.config.broker
  implicit val networkType: NetworkType = node.config.network.networkType
  implicit val askTimeout: Timeout      = Timeout(apiConfig.askTimeout.asScala)
  lazy val blockflowFetchMaxAge         = apiConfig.blockflowFetchMaxAge

  private val vertx         = Vertx.vertx()
  private val vertxEventBus = vertx.eventBus()
  private val server        = vertx.createHttpServer()

  val eventHandler: ActorRef = system.actorOf(EventHandler.props(vertxEventBus))

  node.eventBus.tell(EventBus.Subscribe, eventHandler)

  server.webSocketHandler { webSocket =>
    webSocket.closeHandler(_ => eventHandler ! EventHandler.Unsubscribe(webSocket.textHandlerID()))

    if (!webSocket.path().equals("/events")) {
      webSocket.reject();
    } else {
      eventHandler ! EventHandler.Subscribe(webSocket.textHandlerID())
    }
  }

  private val wsBindingPromise: Promise[HttpServer] = Promise()

  override def subServices: ArraySeq[Service] = ArraySeq(node)

  protected def startSelfOnce(): Future[Unit] = {
    for {
      wsBinding <- server.listen(wsPort, apiConfig.networkInterface.getHostAddress).asScala
    } yield {
      logger.info(s"Listening ws request on ${wsBinding.actualPort}")
      wsBindingPromise.success(wsBinding)
    }
  }

  protected def stopSelfOnce(): Future[Unit] = {
    for {
      _ <- wsBindingPromise.future.flatMap(_.close().asScala)
    } yield {
      logger.info(s"ws unbound")
      ()
    }
  }
}

object WebSocketServer {

  def apply(node: Node)(implicit
      system: ActorSystem,
      apiConfig: ApiConfig,
      executionContext: ExecutionContext
  ): WebSocketServer = {
    val wsPort = node.config.network.wsPort
    new WebSocketServer(node, wsPort)
  }

  object EventHandler {
    def props(
        vertxEventBus: VertxEventBus
    )(implicit networkType: NetworkType, apiConfig: ApiConfig): Props = {
      Props(new EventHandler(vertxEventBus))
    }

    final case class Subscribe(address: String)
    final case class Unsubscribe(address: String)
    case object ListSubscribers
  }
  class EventHandler(vertxEventBus: VertxEventBus)(implicit
      val networkType: NetworkType,
      apiConfig: ApiConfig
  ) extends BaseActor
      with ApiModelCodec {

    lazy val blockflowFetchMaxAge = apiConfig.blockflowFetchMaxAge

    private val subscribers: mutable.HashSet[String] = mutable.HashSet.empty

    def receive: Receive = {
      case event: EventBus.Event =>
        subscribers.foreach { subscriber =>
          vertxEventBus.send(subscriber, handleEvent(event, networkType))
        }
      case EventHandler.Subscribe(subscriber) =>
        if (!subscribers.contains(subscriber)) { subscribers += subscriber }
      case EventHandler.Unsubscribe(subscriber) =>
        if (subscribers.contains(subscriber)) { subscribers -= subscriber }
      case EventHandler.ListSubscribers =>
        sender() ! AVector.unsafe(subscribers.toArray)
    }
  }

  def handleEvent(event: EventBus.Event, networkType: NetworkType)(implicit
      writer: Writer[BlockEntry]
  ): String = {
    event match {
      case bn @ FlowHandler.BlockNotify(_, _) =>
        val params       = blockNotifyEncode(bn, networkType)
        val notification = Notification("block_notify", params)
        write(notification)
    }
  }
  private def blockHeaderEntryfrom(
      blockNotify: BlockNotify,
      networkType: NetworkType
  ): BlockEntry = {
    BlockEntry.from(blockNotify.block, blockNotify.height, networkType)
  }

  def blockNotifyEncode(blockNotify: BlockNotify, networkType: NetworkType)(implicit
      writer: Writer[BlockEntry]
  ): ujson.Value =
    writeJs(blockHeaderEntryfrom(blockNotify, networkType))
}
