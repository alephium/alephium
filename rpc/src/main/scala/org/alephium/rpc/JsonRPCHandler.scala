package org.alephium.rpc

import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.Flow
import com.typesafe.scalalogging.StrictLogging
import io.circe.Json
import io.circe.parser._
import io.circe.syntax._

import model.JsonRPC._

object JsonRPCHandler extends StrictLogging {
  def failure(error: Error): Response = Response.Failure(Json.Null, error)

  def handleRequest(handler: Handler, text: String): Response =
    parse(text) match {
      case Right(json) =>
        handleRequestJson(handler, json)
      case Left(parsingFailure) =>
        logger.debug(s"Unable to parse JSON-RPC request. (${parsingFailure})")
        failure(Error.ParseError)
    }

  def handleRequestJson(handler: Handler, json: Json): Response =
    json.as[RequestUnsafe] match {
      case Right(requestUnsafe) =>
        requestUnsafe.validate(handler) match {
          case Right(request) => handler(request.method)(request)
          case Left(error)    => requestUnsafe.failure(error)
        }
      case Left(decodingFailure) =>
        logger.debug(s"Unable to decode JSON-RPC request. (${decodingFailure})")
        failure(Error.InvalidRequest)
    }

  def handleWebSocketRPC(handler: Handler): Flow[Message, Message, Any] =
    Flow[Message].collect {
      case request: TextMessage.Strict =>
        val response = handleRequest(handler, request.text).asJson
        TextMessage(response.toString)

      case request: TextMessage.Streamed =>
        logger.debug(
          s"Unsupported binary data received, was expecting JSON-RPC text request. (${request})")
        TextMessage(failure(Error.InternalError).asJson.toString)
    }

  def route(handler: Handler): Route =
    get {
      handleWebSocketMessages(handleWebSocketRPC(handler))
    } ~
      post {
        decodeRequest {
          entity(as[String]) { text =>
            complete(handleRequest(handler, text).asJson.toString)
          }
        }
      }
}
