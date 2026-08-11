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

package org.alephium.http

import scala.collection.immutable.ArraySeq
import scala.concurrent.{ExecutionContext, Future}

import com.typesafe.scalalogging.StrictLogging
import io.vertx.core.Vertx
import io.vertx.core.http.{HttpServer, HttpServerOptions}
import sttp.tapir.server.vertx.VertxFutureServerInterpreter._

import org.alephium.util.Service

class HttpService(httpServerOptions: HttpServerOptions)(implicit
    val executionContext: ExecutionContext
) extends Service
    with StrictLogging {

  private var _vertx: Vertx           = _
  private var _httpServer: HttpServer = _

  def httpServer: HttpServer = _httpServer
  def vertx: Vertx           = _vertx

  def listen(port: Int, address: String): Future[HttpServer] = {
    if (_httpServer == null) {
      Future.failed(new IllegalStateException("HttpServer is not initialized"))
    } else {
      _httpServer.listen(port, address).asScala.map { _ =>
        logger.info(s"HttpServer listening on $address:$port")
        _httpServer
      }
    }
  }

  override def startSelfOnce(): Future[Unit] = {
    _vertx = Vertx.vertx()
    _httpServer = _vertx.createHttpServer(httpServerOptions)
    logger.info("Starting service: HttpService")
    Future.successful(())
  }

  override def stopSelfOnce(): Future[Unit] = {
    val closeHttpServer = if (_httpServer != null) {
      _httpServer.close().asScala
    } else {
      Future.successful(())
    }

    closeHttpServer.flatMap { _ =>
      if (_vertx != null) {
        _vertx.close().asScala.map(_ => ())
      } else {
        Future.successful(())
      }
    }
  }

  override def subServices: ArraySeq[Service] = ArraySeq.empty
}
