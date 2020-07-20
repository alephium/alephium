package org.alephium.rpc.model

import scala.concurrent.Future

import com.typesafe.scalalogging.StrictLogging
import io.circe._
import io.circe.generic.semiauto._
import io.circe.syntax._

/* Ref: https://www.jsonrpc.org/specification
 *
 * The only difference is that the type for response we use here is Option[Long]
 */

object JsonRPC extends StrictLogging {
  type Handler = Map[String, Request => Future[Response]]

  val versionKey: String = "jsonrpc"
  val version: String    = "2.0"

  private def paramsCheck(json: Json): Boolean = json.isObject || json.isArray
  private def versionSet(json: Json): Json =
    json.mapObject(_.+:(versionKey -> Json.fromString(version)))

  final case class Error(code: Int, message: String, data: Option[String])
  object Error {
    def apply(code: Int, message: String): Error = {
      Error(code, message, None)
    }

    // scalastyle:off magic.number
    val ParseError: Error        = Error(-32700, "Parse error")
    val InvalidRequest: Error    = Error(-32600, "Invalid Request")
    val MethodNotFound: Error    = Error(-32601, "Method not found")
    val InvalidParams: Error     = Error(-32602, "Invalid params")
    val InternalError: Error     = Error(-32603, "Internal error")
    val UnauthorizedError: Error = Error(-32604, "Unauthorized")

    def server(error: String): Error = Error(-32000, "Server error", Some(error))
    // scalastyle:on
  }

  trait WithId { def id: Long }

  final case class RequestUnsafe(
      jsonrpc: String,
      method: String,
      params: Json,
      id: Long
  ) extends WithId {
    def runWith(handler: Handler): Future[Response] = {
      if (jsonrpc == JsonRPC.version) {
        handler.get(method) match {
          case Some(f) if paramsCheck(params) =>
            f(Request(method, params, id))
          case Some(_) =>
            Future.successful(Response.failed(this, Error.InvalidParams))
          case None =>
            Future.successful(Response.failed(this, Error.MethodNotFound))
        }
      } else {
        Future.successful(Response.failed(this, Error.InvalidRequest))
      }
    }
  }
  object RequestUnsafe {
    implicit val decoder: Decoder[RequestUnsafe] = deriveDecoder[RequestUnsafe]
  }

  final case class Request(method: String, params: Json, id: Long) extends WithId {
    def paramsAs[A: Decoder]: Either[Response.Failure, A] =
      params.as[A] match {
        case Right(a) => Right(a)
        case Left(decodingFailure) =>
          logger.debug(
            s"Unable to decode JsonRPC request parameters. ($method@$id: $decodingFailure)")
          Left(Response.failed(this, Error.InvalidParams))
      }
  }
  object Request {
    implicit val encoder: Encoder[Request] = deriveEncoder[Request].mapJson(versionSet)
  }

  final case class NotificationUnsafe(jsonrpc: String, method: String, params: Option[Json]) {
    def asNotification: Either[Error, Notification] =
      if (jsonrpc == JsonRPC.version) {
        params match {
          case Some(paramsExtracted) if paramsCheck(paramsExtracted) =>
            Right(Notification(method, paramsExtracted))
          case _ => Left(Error.InvalidParams)
        }
      } else {
        Left(Error.InvalidRequest)
      }
  }
  object NotificationUnsafe {
    implicit val decoder: Decoder[NotificationUnsafe] = deriveDecoder[NotificationUnsafe]
  }

  final case class Notification(method: String, params: Json)
  object Notification {
    implicit val encoder: Encoder[Notification] = deriveEncoder[Notification].mapJson(versionSet)
  }

  sealed trait Response
  object Response {
    def failed[T <: WithId](request: T, error: Error): Failure = Failure(error, Some(request.id))
    def failed(error: Error): Failure                          = Failure(error, None)
    def failed(error: String): Failure                         = failed(Error.server(error))
    def successful[T <: WithId](request: T): Success           = Success(Json.True, request.id)
    def successful[T <: WithId, R](request: T, result: R)(implicit encoder: Encoder[R]): Success =
      Success(result.asJson, request.id)

    final case class Success(result: Json, id: Long) extends Response
    object Success {
      implicit val codec: Codec[Success] = deriveCodec[Success]
    }
    final case class Failure(error: Error, id: Option[Long]) extends Response
    object Failure {
      import io.circe.generic.auto._ // Note: I hate this!
      implicit val codec: Codec[Failure] = deriveCodec[Failure]
    }

    implicit val decoder: Decoder[Response] = new Decoder[Response] {
      final def apply(cursor: HCursor): Decoder.Result[Response] = {
        cursor.get[String](versionKey) match {
          case Right(v) if v == version =>
            if (cursor.keys.exists(_.exists(_ == "result"))) {
              Success.codec(cursor)
            } else {
              Failure.codec(cursor)
            }
          case Right(v)    => Left(DecodingFailure(s"Invalid JSON-RPC version '$v'", cursor.history))
          case Left(error) => Left(error)
        }
      }
    }

    implicit val encoder: Encoder[Response] = {
      val product: Encoder[Response] = Encoder.instance {
        case x @ Success(_, _) => x.asJson
        case x @ Failure(_, _) => x.asJson
      }
      product.mapJson(versionSet)
    }
  }
}
