package org.alephium.rpc

import scala.concurrent.{ExecutionContext, Future}
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

  def handleRequest(handler: Handler, text: String): Future[Response] =
    parse(text) match {
      case Right(json) =>
        handleRequestJson(handler, json)
      case Left(parsingFailure) =>
        logger.debug(s"Unable to parse JSON-RPC request. (${parsingFailure})")
        Future.successful(failure(Error.ParseError))
    }

  def handleRequestJson(handler: Handler, json: Json): Future[Response] =
    json.as[RequestUnsafe] match {
      case Right(requestUnsafe) =>
        requestUnsafe.validate(handler) match {
          case Right(request) => handler(request.method)(request)
          case Left(error)    => Future.successful(requestUnsafe.failure(error))
        }
      case Left(decodingFailure) =>
        logger.debug(s"Unable to decode JSON-RPC request. (${decodingFailure})")
        Future.successful(failure(Error.InvalidRequest))
    }

  def handleWebSocketRPC(handler: Handler)(
      implicit EC: ExecutionContext): Flow[Message, Message, Any] =
    Flow[Message].mapAsync(1) {
      case TextMessage.Strict(text) =>
        handleRequest(handler, text).map { response =>
          TextMessage(response.asJson.toString)
        }

      case message =>
        logger.debug(
          s"Unsupported web socket message received, was expecting JSON-RPC strict text request. (${message})")
        Future.successful(TextMessage(failure(Error.InternalError).asJson.toString))
    }

  def route(handler: Handler)(implicit EC: ExecutionContext): Route =
    get {
      handleWebSocketMessages(handleWebSocketRPC(handler))
    } ~
      post {
        decodeRequest {
          entity(as[String]) { text =>
            onSuccess(handleRequest(handler, text)) { response =>
              complete(response.asJson.toString)
            }
          }
        }
      }
}
