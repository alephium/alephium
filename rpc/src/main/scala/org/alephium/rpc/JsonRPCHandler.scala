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
