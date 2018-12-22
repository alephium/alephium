package org.alephium.rpc

import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.server.{Route}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.headers._

trait CORSHandler {
  private val corsResponseHeaders = List(
    `Access-Control-Allow-Origin`.*,
    `Access-Control-Allow-Credentials`(true),
    `Access-Control-Allow-Headers`("Authorization", "Content-Type", "X-Requested-With")
  )

  private def preflightRequestHandler: Route = options {
    complete(
      HttpResponse(StatusCodes.OK)
        .withHeaders(`Access-Control-Allow-Methods`(OPTIONS, POST, PUT, GET, DELETE)))
  }

  def corsHandler(r: Route): Route = respondWithHeaders(corsResponseHeaders) {
    preflightRequestHandler ~ r
  }

  def addCORSHeaders(response: HttpResponse): HttpResponse =
    response.withHeaders(corsResponseHeaders)
}
