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
import scala.concurrent._

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.{CompletionStrategy, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.Timeout
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import com.typesafe.scalalogging.StrictLogging
import io.circe._
import io.circe.syntax._

import org.alephium.api.ApiModelCodec
import org.alephium.api.CirceUtils
import org.alephium.api.model._
import org.alephium.flow.client.Node
import org.alephium.flow.handler.FlowHandler
import org.alephium.flow.handler.FlowHandler.BlockNotify
import org.alephium.protocol.config.{ChainsConfig, GroupConfig}
import org.alephium.protocol.model.NetworkType
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util.{ActorRefT, Duration, EventBus, Service}

class WebSocketServer(node: Node, wsPort: Int)(implicit val system: ActorSystem,
                                               val apiConfig: ApiConfig,
                                               val executionContext: ExecutionContext)
    extends ApiModelCodec
    with StrictLogging
    with Service {
  import WebSocketServer._

  implicit val groupConfig: GroupConfig   = node.config.broker
  implicit val chainsConfig: ChainsConfig = node.config.chains
  implicit val networkType: NetworkType   = node.config.chains.networkType
  implicit val askTimeout: Timeout        = Timeout(apiConfig.askTimeout.asScala)
  lazy val blockflowFetchMaxAge           = apiConfig.blockflowFetchMaxAge

  private val terminationHardDeadline = Duration.ofSecondsUnsafe(10).asScala

  private def wsFlow(eventBus: ActorRefT[EventBus.Message],
                     actor: ActorRef,
                     source: Source[Nothing, NotUsed]): Flow[Any, TextMessage, Unit] = {
    Flow
      .fromSinkAndSourceCoupled(Sink.ignore, source.map(handleEvent))
      .watchTermination() { (_, termination) =>
        termination.onComplete(_ => eventBus.tell(EventBus.Unsubscribe, actor))
      }
  }

  private def routeWs(eventBus: ActorRefT[EventBus.Message]): Route = {
    path("events") {
      cors()(get {
        extractUpgradeToWebSocket { upgrade =>
          val (actor, source) = Websocket.actorRef
          eventBus.tell(EventBus.Subscribe, actor)
          val response = upgrade.handleMessages(wsFlow(eventBus, actor, source))
          complete(response)
        }
      })
    }
  }

  private def doBlockNotify(blockNotify: BlockNotify): Json =
    blockNotifyEncode(blockNotify)

  private def handleEvent(event: EventBus.Event): TextMessage = {
    event match {
      case bn @ FlowHandler.BlockNotify(_, _) =>
        val params       = doBlockNotify(bn)
        val notification = Notification("block_notify", params)
        TextMessage(CirceUtils.print(notification.asJson))
    }
  }

  val wsRoute: Route = routeWs(node.eventBus)

  private val wsBindingPromise: Promise[Http.ServerBinding] = Promise()

  override def subServices: ArraySeq[Service] = ArraySeq(node)

  protected def startSelfOnce(): Future[Unit] = {
    for {
      wsBinding <- Http()
        .bindAndHandle(wsRoute, apiConfig.networkInterface.getHostAddress, wsPort)
    } yield {
      logger.info(s"Listening ws request on $wsBinding")
      wsBindingPromise.success(wsBinding)
    }
  }

  protected def stopSelfOnce(): Future[Unit] = {
    for {
      wsBinding <- wsBindingPromise.future
      wsStop    <- wsBinding.terminate(hardDeadline = terminationHardDeadline)
    } yield {
      logger.info(s"ws unbound with message $wsStop.")
      ()
    }
  }
}

object WebSocketServer {

  val bufferSize: Int = 64

  def apply(node: Node)(implicit system: ActorSystem,
                        apiConfig: ApiConfig,
                        executionContext: ExecutionContext): WebSocketServer = {
    val wsPort = node.config.network.wsPort
    new WebSocketServer(node, wsPort)
  }

  private def blockEntryfrom(blockNotify: BlockNotify)(implicit config: GroupConfig): BlockEntry = {
    BlockEntry.from(blockNotify.header, blockNotify.height)
  }

  def blockNotifyEncode(blockNotify: BlockNotify)(implicit config: GroupConfig,
                                                  encoder: Encoder[BlockEntry]): Json =
    blockEntryfrom(blockNotify).asJson

  object Websocket {

    case object Completed
    case object Failed

    def actorRef(implicit system: ActorSystem): (ActorRef, Source[Nothing, NotUsed]) =
      Source
        .actorRef(
          {
            case Websocket.Completed =>
              CompletionStrategy.draining
          }, {
            case Websocket.Failed =>
              new Throwable("failure on events websocket")
          },
          bufferSize,
          OverflowStrategy.fail
        )
        .preMaterialize()

  }
}
