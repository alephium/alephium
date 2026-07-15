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

package org.alephium.ws

import java.util.concurrent.ConcurrentSkipListMap

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

import com.typesafe.scalalogging.StrictLogging
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.{
  WebSocket,
  WebSocketClient,
  WebSocketClientOptions,
  WebSocketConnectOptions
}

import org.alephium.api.model.ApiKey
import org.alephium.json.Json._
import org.alephium.protocol.model.Address
import org.alephium.rpc.model.JsonRPC
import org.alephium.rpc.model.JsonRPC.{Notification, Response}
import org.alephium.util.AVector
import org.alephium.ws.WsClient._
import org.alephium.ws.WsParams.{
  ContractEventsSubscribeParams,
  SimpleSubscribeParams,
  WsCorrelationId,
  WsEventIndex,
  WsSubscriptionId
}
import org.alephium.ws.WsUtils._

object WsClient {
  final case class KeepAlive(data: Buffer)

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  final case class WsException(message: String, source: Option[Throwable] = None)
      extends RuntimeException(message, source.orNull)
}

/** Factory for creating WebSocket client connections */
final case class WsClientFactory(underlying: WebSocketClient) {

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def connect(
      port: Int,
      host: String = "127.0.0.1",
      uri: String = "/ws",
      apiKey: Option[ApiKey] = None
  )(
      notificationHandler: Notification => Unit
  )(
      keepAliveHandler: KeepAlive => Unit
  )(implicit ec: ExecutionContext): Future[WsClient] = {
    val options = new WebSocketConnectOptions()
      .setPort(port)
      .setHost(host)
      .setURI(uri)
    apiKey.foreach(key => options.addHeader(WsSubscriptionHandler.ApiKeyHeader, key.value))
    underlying
      .connect(options)
      .asScala
      .map(underlying => WsClient(underlying, notificationHandler, keepAliveHandler))
  }
}

object WsClientFactory {
  def apply(vertx: Vertx): WsClientFactory = {
    WsClientFactory(vertx.createWebSocketClient())
  }

  def apply(vertx: Vertx, options: WebSocketClientOptions): WsClientFactory = {
    WsClientFactory(vertx.createWebSocketClient(options))
  }
}

/** An active WebSocket client connection with subscription capabilities */
final case class WsClient(
    underlying: WebSocket,
    notificationHandler: Notification => Unit,
    keepAliveHandler: KeepAlive => Unit
)(implicit
    ec: ExecutionContext
) extends StrictLogging {

  private val ongoingRequests =
    new ConcurrentSkipListMap[WsCorrelationId, Promise[Response]]().asScala
  private var terminalFailure: Option[WsException] = None

  underlying.closeHandler(_ =>
    failOngoingRequests(
      WsException("WebSocket connection closed before receiving a response.")
    )
  )

  underlying.exceptionHandler(exception =>
    failOngoingRequests(
      WsException(
        s"WebSocket connection failed before receiving a response: ${exception.getMessage}",
        Option(exception)
      )
    )
  )

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

  def writeRequestToSocket(request: WsRequest): Future[Response] = {
    reserveRequest(request.id) match {
      case Left(failure) => Future.failed(failure)
      case Right(promise) =>
        underlying
          .writeTextMessage(write(request))
          .asScala
          .onComplete {
            case Success(_) =>
            case Failure(exception) =>
              removeRequest(request.id, promise)
              promise
                .tryFailure(
                  WsException(
                    s"Failed to write message with id ${request.id}: ${exception.getMessage}",
                    Option(exception)
                  )
                )
              ()
          }
        promise.future
    }
  }

  private def reserveRequest(
      id: WsCorrelationId
  ): Either[WsException, Promise[Response]] = ongoingRequests.synchronized {
    terminalFailure match {
      case Some(failure) => Left(failure)
      case None =>
        if (ongoingRequests.contains(id)) {
          Left(WsException(s"Request with id $id is being already handled."))
        } else {
          val promise = Promise[Response]()
          ongoingRequests.put(id, promise)
          Right(promise)
        }
    }
  }

  private def removeRequest(id: WsCorrelationId, promise: Promise[Response]): Unit =
    ongoingRequests.synchronized {
      ongoingRequests.get(id) match {
        case Some(current) if current eq promise =>
          ongoingRequests.remove(id)
          ()
        case _ => ()
      }
    }

  private def failOngoingRequests(failure: WsException): Unit = {
    val (promises, effectiveFailure) = ongoingRequests.synchronized {
      val effectiveFailure = terminalFailure.getOrElse(failure)
      terminalFailure = Some(effectiveFailure)
      val promises = ongoingRequests.values.toVector
      ongoingRequests.clear()
      promises -> effectiveFailure
    }
    promises.foreach(_.tryFailure(effectiveFailure))
  }

  private def handleResponse(message: ujson.Value): Unit = {
    read[Response](message) match {
      case r @ JsonRPC.Response.Success(_, id) =>
        ongoingRequests.remove(id).foreach(_.trySuccess(r))
      case r @ JsonRPC.Response.Failure(_, Some(id)) =>
        ongoingRequests.remove(id).foreach(_.trySuccess(r))
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
      addresses: AVector[Address.Contract],
      eventIndex: Option[WsEventIndex]
  ): Future[Response] = {
    writeRequestToSocket(
      WsRequest.subscribe(id, ContractEventsSubscribeParams(addresses, eventIndex))
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

  def close(): Future[Unit] = underlying.close().asScala.map(_ => ())
}
