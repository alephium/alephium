package org.alephium.rpc.model

import scala.concurrent.Future

import com.typesafe.scalalogging.StrictLogging
import io.circe._

// https://www.jsonrpc.org/specification

object JsonRPC extends StrictLogging {
  type Handler = PartialFunction[String, Request => Future[Response]]

  val versionKey = "jsonrpc"
  val version    = "2.0"

  def versionSet(json: Json): Json = json.mapObject(_.add(versionKey, Json.fromString(version)))

  case class Error(code: Int, message: String)

  object Error {
    // scalastyle:off magic.number
    val ParseError     = Error(-32700, "Unable to parse request.")
    val InvalidRequest = Error(-32600, "The request is invalid.")
    val MethodNotFound = Error(-32601, "Method not found.")
    val InvalidParams  = Error(-32602, "Invalid parameters.")
    val InternalError  = Error(-32603, "Internal error.")
    // scalastyle:on
  }

  case class RequestUnsafe(
      id: Json,
      method: String,
      params: Json,
      jsonrpc: String
  ) {
    def failure(error: Error): Response = Response.Failure(id, error)
    def validate(handler: Handler): Either[Error, Request] = {
      if (jsonrpc == JsonRPC.version) {
        if (handler.isDefinedAt(method)) {
          Right(Request(id, method, params))
        } else {
          Left(Error.MethodNotFound)
        }
      } else {
        Left(Error.InvalidRequest)
      }
    }
  }

  object RequestUnsafe {
    import io.circe.generic.semiauto._
    implicit val decoder: Decoder[RequestUnsafe] = deriveDecoder[RequestUnsafe]
  }

  case class Request(id: Json, method: String, params: Json) {
    def paramsAs[A: Decoder]: Either[Response, A] = params.as[A] match {
      case Right(a) => Right(a)
      case Left(decodingFailure) =>
        logger.debug(
          s"Unable to decode JsonRPC request parameters. ($method@$id: ${decodingFailure})")
        Left(failure(Error.InvalidParams))
    }

    def successful(): Response          = Response.Success(id, Json.True)
    def success(result: Json): Response = Response.Success(id, result)
    def failure(error: Error): Response = Response.Failure(id, error)
  }

  object Request {
    import io.circe.generic.semiauto._
    implicit val encoder: Encoder[Request] = deriveEncoder[Request].mapJson(versionSet)
  }

  sealed trait Response

  object Response {
    import io.circe.syntax._
    import io.circe.generic.auto._

    case class Success(id: Json, result: Json) extends Response
    object Success {
      import io.circe.generic.semiauto._
      implicit val decoder: Decoder[Success] = deriveDecoder[Success]
    }
    case class Failure(id: Json, error: Error) extends Response
    object Failure {
      import io.circe.generic.semiauto._
      implicit val decoder: Decoder[Failure] = deriveDecoder[Failure]
    }

    implicit val encoder: Encoder[Response] = {
      val product: Encoder[Response] = Encoder.instance {
        case x @ Success(_, _) => x.asJson
        case x @ Failure(_, _) => x.asJson
      }
      product.mapJson(versionSet)
    }

    // TODO Rewrite better implementation than type casting/failover
    implicit val decoder: Decoder[Response] =
      Success.decoder.or[Response](Failure.decoder.asInstanceOf[Decoder[Response]])

  }
}
