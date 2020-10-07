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

package org.alephium.rpc

import scala.concurrent.Future

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.typesafe.scalalogging.StrictLogging
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.Json
import model.JsonRPC._

object JsonRPCHandler extends StrictLogging {
  private def handleRequest(handler: Handler, json: Json): Future[Response] =
    json.as[RequestUnsafe] match {
      case Right(requestUnsafe) =>
        requestUnsafe.runWith(handler)
      case Left(decodingFailure) =>
        val jsonString    = CirceUtils.print(json)
        val failureString = decodingFailure.message
        logger.debug(s"Unable to decode JSON-RPC request $jsonString. ($failureString)")
        val response = Response.failed(Error.InvalidRequest)
        Future.successful(response)
    }

  def routeHttp(handler: Handler): Route =
    post {
      entity(as[Json]) { json =>
        onSuccess(handleRequest(handler, json)) { response =>
          complete(response)
        }
      }
    }
}
