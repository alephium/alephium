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

import java.util.concurrent.ConcurrentSkipListMap

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

import com.typesafe.scalalogging.StrictLogging
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.{WebSocket, WebSocketClient, WebSocketClientOptions}

import org.alephium.app.ws.ClientWs.WsException
import org.alephium.app.ws.WsClient.KeepAlive
import org.alephium.app.ws.WsParams.{
  ContractEventsSubscribeParams,
  SimpleSubscribeParams,
  WsCorrelationId,
  WsSubscriptionId
}
import org.alephium.app.ws.WsUtils._
import org.alephium.json.Json._
import org.alephium.protocol.model.Address
import org.alephium.rpc.model.JsonRPC
import org.alephium.rpc.model.JsonRPC.{Notification, Response}
import org.alephium.util.AVector

object ClientWs {
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  final case class WsException(message: String, source: Option[Throwable] = None)
      extends RuntimeException(message, source.orNull)
}

final case class WsClient(underlying: WebSocketClient) {

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def connect(
      port: Int,
      host: String = "127.0.0.1",
      uri: String = "/ws"
  )(
      notificationHandler: Notification => Unit
  )(keepAliveHandler: KeepAlive => Unit)(implicit ec: ExecutionContext): Future[ClientWs] = {
    underlying
      .connect(port, host, uri)
      .asScala
      .map(underlying => ClientWs(underlying, notificationHandler, keepAliveHandler))
  }
}

object WsClient {
  final case class KeepAlive(data: Buffer)

  def apply(vertx: Vertx): WsClient = {
    WsClient(vertx.createWebSocketClient())
  }

  def apply(vertx: Vertx, options: WebSocketClientOptions): WsClient = {
    WsClient(vertx.createWebSocketClient(options))
  }
}

final case class ClientWs(
    underlying: WebSocket,
    notificationHandler: Notification => Unit,
    keepAliveHandler: KeepAlive => Unit
)(implicit
    ec: ExecutionContext
) extends StrictLogging {

  underlying.frameHandler { frame =>
    if (frame.isPing) {
      underlying.writePong(Buffer.buffer("pong")).asScala.onComplete {
        case Success(_) =>
          keepAliveHandler(KeepAlive(frame.binaryData()))
        case Failure(ex) =>
          logger.error("Websocket keep-alive failure", ex)
      }
    }
  }

  underlying.textMessageHandler((message: String) =>
    Try(ujson.read(message)) match {
      case Success(jsonMessage) =>
        if (jsonMessage.obj.contains("method")) {
          notificationHandler(read[Notification](jsonMessage))
        } else if (jsonMessage.obj.contains("result") || jsonMessage.obj.contains("error")) {
          handleResponse(jsonMessage)
        } else {
          logger.warn(s"Unsupported message: $message")
        }
      case Failure(_) =>
        logger.warn(s"Unsupported message: $message")
    }
  )

  private val ongoingRequests =
    new ConcurrentSkipListMap[WsCorrelationId, Promise[Response]]().asScala

  protected[ws] def writeRequestToSocket(request: WsRequest): Future[Response] = {
    if (ongoingRequests.contains(request.id.id)) {
      Future.failed(WsException(s"Request with id ${request.id.id} already executed."))
    } else {
      val promise = Promise[Response]()
      underlying
        .writeTextMessage(write(request))
        .asScala
        .onComplete {
          case Success(_) =>
            ongoingRequests.put(request.id.id, promise)
          case Failure(exception) =>
            ongoingRequests.remove(request.id.id).foreach { _ =>
              promise
                .failure(
                  WsException(
                    s"Failed to write message with id ${request.id.id}: ${exception.getMessage}",
                    Option(exception)
                  )
                )
            }
        }
      promise.future
    }
  }

  private def handleResponse(message: ujson.Value): Unit = {
    read[Response](message) match {
      case r @ JsonRPC.Response.Success(_, id) =>
        ongoingRequests.remove(id).foreach(_.success(r))
      case r @ JsonRPC.Response.Failure(_, Some(id)) =>
        ongoingRequests.remove(id).foreach(_.success(r))
      case JsonRPC.Response.Failure(ex, None) =>
        logger.error(s"Response without id is not supported", ex)
    }
  }

  def subscribeToBlock(id: WsCorrelationId): Future[Response] = {
    writeRequestToSocket(WsRequest.subscribe(id, SimpleSubscribeParams.Block))
  }

  def subscribeToTx(id: WsCorrelationId): Future[Response] = {
    writeRequestToSocket(WsRequest.subscribe(id, SimpleSubscribeParams.Tx))
  }

  def subscribeToContractEvents(
      id: WsCorrelationId,
      eventIndex: Int,
      addresses: AVector[Address.Contract]
  ): Future[Response] = {
    writeRequestToSocket(
      WsRequest.subscribe(id, ContractEventsSubscribeParams.from(eventIndex, addresses))
    )
  }

  def unsubscribeFromBlock(id: WsCorrelationId): Future[Response] = {
    writeRequestToSocket(WsRequest.unsubscribe(id, SimpleSubscribeParams.Block.subscriptionId))
  }

  def unsubscribeFromTx(id: WsCorrelationId): Future[Response] = {
    writeRequestToSocket(WsRequest.unsubscribe(id, SimpleSubscribeParams.Tx.subscriptionId))
  }

  def unsubscribeFromContractEvents(
      id: WsCorrelationId,
      subscriptionId: WsSubscriptionId
  ): Future[Response] = {
    writeRequestToSocket(WsRequest.unsubscribe(id, subscriptionId))
  }

  def isClosed: Boolean = underlying.isClosed

  def close(): Future[Unit] = underlying.close().asScala.mapTo[Unit]
}
