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

package org.alephium.rpc.model

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

import com.typesafe.scalalogging.StrictLogging

import org.alephium.json.Json._

/* Ref: https://www.jsonrpc.org/specification
 *
 * The only difference is that the type for response we use here is Option[Long]
 */

@SuppressWarnings(Array("org.wartremover.warts.ToString"))
object JsonRPC extends StrictLogging {
  type Handler = Map[String, Request => Future[Response]]

  val versionKey: String = "jsonrpc"
  val version: String    = "2.0"

  private def paramsCheck(json: ujson.Value): Boolean = json match {
    case ujson.Obj(_) | ujson.Arr(_) => true
    case _                           => false
  }
  private def versionSet(json: ujson.Value): ujson.Value = {
    json match {
      case ujson.Obj(obj) => obj.addOne(versionKey -> ujson.Str(version))
      case other          => other
    }
  }

  private def writerWithVersion[A](tmpWriter: Writer[A]): Writer[A] = writer[ujson.Value].comap {
    request =>
      versionSet(writeJs(request)(tmpWriter))
  }

  final case class Error(code: Int, message: String, data: Option[String])
  object Error {
    implicit val errorReadWriter: ReadWriter[Error] = {
      readwriter[ujson.Value].bimap[Error](
        { error =>
          error.data match {
            case None =>
              ujson.Obj("code" -> writeJs(error.code), "message" -> ujson.Str(error.message))
            case Some(data) =>
              ujson.Obj(
                "code"    -> writeJs(error.code),
                "message" -> ujson.Str(error.message),
                "data"    -> ujson.Str(data)
              )
          }
        },
        { json =>
          Error(
            read[Int](json("code")),
            read[String](json("message")),
            readOpt[String](json("data"))
          )
        }
      )
    }

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
      params: ujson.Value,
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
    def apply(jsonrpc: String, method: String, params: ujson.Value, id: Long): RequestUnsafe = {
      new RequestUnsafe(jsonrpc, method, dropNullValues(params), id)
    }
    implicit val requestUnsafeReader: Reader[RequestUnsafe] = macroR[RequestUnsafe]
  }

  final case class Request private (method: String, params: ujson.Value, id: Long) extends WithId {
    def paramsAs[A: Reader]: Either[Response.Failure, A] =
      Try(read[A](params)) match {
        case Success(a) => Right(a)
        case Failure(readingFailure) =>
          logger.debug(
            s"Unable to read JsonRPC request parameters. ($method@$id: $readingFailure)"
          )
          Left(Response.failed(this, Error.InvalidParams))
      }
  }
  object Request {
    def apply(method: String, params: ujson.Value, id: Long): Request = {
      new Request(method, dropNullValues(params), id)
    }
    implicit val requestWriter: Writer[Request] = writerWithVersion(macroW[Request])
  }

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  final case class NotificationUnsafe(
      jsonrpc: String,
      method: String,
      params: Option[ujson.Value] = None
  ) {
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
    def apply(jsonrpc: String, method: String, params: Option[ujson.Value]): NotificationUnsafe = {
      new NotificationUnsafe(jsonrpc, method, params.map(dropNullValues))
    }
    implicit val notificationUnsafeReader: Reader[NotificationUnsafe] =
      reader[ujson.Value].map[NotificationUnsafe] { json =>
        NotificationUnsafe(
          read[String](json("jsonrpc")),
          read[String](json("method")),
          readOpt[ujson.Value](json("params"))
        )
      }
  }

  final case class Notification private (method: String, params: ujson.Value)
  object Notification {
    def apply(method: String, params: ujson.Value): Notification = {
      new Notification(method, dropNullValues(params))
    }
    implicit val notificationWriter: Writer[Notification] = writerWithVersion(macroW[Notification])
    implicit val notificationRead: Reader[Notification]   = macroR[Notification]
  }

  sealed trait Response
  object Response {
    def failed[T <: WithId](request: T, error: Error): Failure = Failure(error, Some(request.id))
    def failed(error: Error): Failure                          = Failure(error, None)
    def failed(error: String): Failure                         = failed(Error.server(error))
    def successful[T <: WithId](request: T): Success           = Success(ujson.True, request.id)
    def successful[T <: WithId, R](request: T, result: R)(implicit writer: Writer[R]): Success =
      Success(writeJs(result), request.id)

    final case class Success private (result: ujson.Value, id: Long) extends Response
    object Success {
      def apply(result: ujson.Value, id: Long): Success = {
        new Success(dropNullValues(result), id)
      }
      implicit val succesReadWriter: ReadWriter[Success] = readwriter[ujson.Value].bimap[Success](
        success =>
          success.result match {
            case ujson.Null => ujson.Obj("id" -> writeJs(success.id))
            case _          => ujson.Obj("result" -> success.result, "id" -> writeJs(success.id))
          },
        json => Success(read[ujson.Value](json("result")), read[Long](json("id")))
      )
    }
    final case class Failure(error: Error, id: Option[Long]) extends Response
    object Failure {
      implicit val failureReadWriter: ReadWriter[Failure] = readwriter[ujson.Value].bimap[Failure](
        failure =>
          failure.id match {
            case Some(id) => ujson.Obj("error" -> writeJs(failure.error), "id" -> writeJs(id))
            case None     => ujson.Obj("error" -> writeJs(failure.error))
          },
        json => Failure(read[Error](json("error")), read[Option[Long]](json("id")))
      )
    }

    implicit val responseReader: Reader[Response] = reader[ujson.Value].map { json =>
      val v = read[String](json(versionKey))
      if (v == version) {
        Try(read[ujson.Value](json("result"))) match {
          case scala.util.Success(_) => read[Success](json)
          case scala.util.Failure(_) => read[Failure](json)
        }
      } else {
        throw upickle.core.Abort(s"Invalid JSON-RPC version '$v'")
      }
    }

    implicit val responseWriter: Writer[Response] = {
      writer[ujson.Value].comap {
        case x @ Success(_, _) => versionSet(writeJs(x))
        case x @ Failure(_, _) => versionSet(writeJs(x))
      }
    }
  }
}
