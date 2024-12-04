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

import scala.concurrent.{ExecutionContext, Future}

import io.vertx.core.{Handler, Vertx}
import io.vertx.core.http.{WebSocket, WebSocketClient, WebSocketClientOptions}

import org.alephium.app.ws.WsParams.{SubscribeParams, WsCorrelationId}
import org.alephium.app.ws.WsUtils._
import org.alephium.json.Json._

final class Ws(underlying: WebSocket) {

  def isClosed: Boolean = underlying.isClosed

  def close(): Future[Unit] = underlying.close().asScala.mapTo[Unit]

  def writeTextMessage(message: String): Future[Unit] = {
    underlying.writeTextMessage(message).asScala.mapTo[Unit]
  }

  def subscribeToBlock(id: WsCorrelationId): Future[Unit] = {
    writeTextMessage(write(WsRequest.subscribe(id, SubscribeParams.Block)))
  }

  def subscribeToTx(id: WsCorrelationId): Future[Unit] = {
    writeTextMessage(write(WsRequest.subscribe(id, SubscribeParams.Tx)))
  }

  def unsubscribeFromBlock(id: WsCorrelationId): Future[Unit] = {
    writeTextMessage(write(WsRequest.unsubscribe(id, SubscribeParams.Block.subscriptionId)))
  }

  def unsubscribeFromTx(id: WsCorrelationId): Future[Unit] = {
    writeTextMessage(write(WsRequest.unsubscribe(id, SubscribeParams.Tx.subscriptionId)))
  }

  def textMessageHandler(handler: String => Unit): Unit = {
    val _ = underlying.textMessageHandler(new Handler[String] {
      override def handle(message: String): Unit = handler(message)
    })
  }
}

final class WsClient(underlying: WebSocketClient) {

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def connect(
      port: Int,
      host: String = "127.0.0.1",
      uri: String = "/ws"
  )(implicit ec: ExecutionContext): Future[Ws] = {
    underlying
      .connect(port, host, uri)
      .asScala
      .map(new Ws(_))
  }
}

object WsClient {

  def apply(vertx: Vertx): WsClient = {
    new WsClient(vertx.createWebSocketClient())
  }

  def apply(vertx: Vertx, options: WebSocketClientOptions): WsClient = {
    new WsClient(vertx.createWebSocketClient(options))
  }
}
