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

package org.alephium.app

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

import com.typesafe.scalalogging.LazyLogging
import io.vertx.core.{Future => VertxFuture}
import io.vertx.core.http.ServerWebSocket

import org.alephium.api.ApiModelCodec
import org.alephium.app.WebSocketServer.{Correlation, SubscriptionId}
import org.alephium.json.Json.{read, write}
import org.alephium.rpc.model.JsonRPC.{Error, RequestUnsafe, Response}
import org.alephium.util.{AVector, Hex}

object WsProtocol {
  object Code {
    val AlreadySubscribed: Int = -32010
  }
}

sealed trait WsMethod {
  def name: String
}
object WsMethod {
  val values: AVector[WsMethod] = AVector(Subscribe, Unsubscribe)
  case object Subscribe   extends WsMethod { val name = "subscribe"   }
  case object Unsubscribe extends WsMethod { val name = "unsubscribe" }
  def fromString(name: String): Either[Error, WsMethod] = name match {
    case Subscribe.name   => Right(Subscribe)
    case Unsubscribe.name => Right(Unsubscribe)
    case unknown =>
      Left(
        Error(
          Error.MethodNotFoundCode,
          s"Invalid method $unknown, expected: ${values.mkString(", ")}"
        )
      )
  }
}
sealed trait WsParams {
  def name: String
  def data: Option[String]           = None
  def subscriptionId: SubscriptionId = Hex.unsafe(name + data.getOrElse("")).toString()
}
object WsParams {
  val values: AVector[WsParams] = AVector(Block, Tx)
  case object Block extends WsParams { val name = "block" }
  case object Tx    extends WsParams { val name = "tx"    }
  private def fromString(name: String): Either[Error, WsParams] = name match {
    case Block.name => Right(Block)
    case Tx.name    => Right(Tx)
    case unknown =>
      Left(
        Error(
          Error.InvalidParamsCode,
          s"Invalid params $unknown, expected: ${values.mkString(", ")}"
        )
      )
  }
  def fromJson(params: ujson.Value): Either[Error, WsParams] = {
    params match {
      case ujson.Arr(arr) if arr.length == 1 =>
        arr(0) match {
          case ujson.Str(name) => fromString(name)
          case unsupported =>
            Left(
              Error(
                Error.InvalidParamsCode,
                s"Invalid params format: $unsupported, expected array of : ${values.mkString(", ")}"
              )
            )
        }
      case unsupported =>
        Left(
          Error(
            Error.InvalidParamsCode,
            s"Invalid params format: $unsupported, expected array of : ${values.mkString(", ")}"
          )
        )
    }
  }
}

final case class WsRequest(id: Correlation, method: WsMethod, params: WsParams)
object WsRequest extends ApiModelCodec {
  def fromJsonString(msg: String): Either[Error, WsRequest] = {
    Try(read[RequestUnsafe](msg)) match {
      case Success(r)  => fromJsonRpc(r)
      case Failure(ex) => Left(Error(Error.ParseErrorCode, ex.getMessage))
    }
  }

  def fromJsonRpc(r: RequestUnsafe): Either[Error, WsRequest] = {
    for {
      params <- WsParams.fromJson(r.params)
      method <- WsMethod.fromString(r.method)
    } yield WsRequest(Correlation(r.id), method, params)
  }
}

trait WsUtils extends LazyLogging {
  implicit class RichVertxFuture[T](val vertxFuture: VertxFuture[T]) {
    def asScala: Future[T] = {
      val promise = Promise[T]()
      vertxFuture.onComplete {
        case handler if handler.succeeded() =>
          promise.success(handler.result())
        case handler if handler.failed() =>
          promise.failure(handler.cause())
      }
      promise.future
    }
  }

  def respondAsyncAndForget(ws: ServerWebSocket, response: Response)(implicit
      ec: ExecutionContext
  ): Unit = {
    Try(write(response)) match {
      case Success(_) =>
        val _ = ws
          .writeTextMessage(write(response))
          .asScala
          .andThen { case Failure(exception) =>
            logger.warn(s"Failed to respond with: $response", exception)
          }
      case Failure(ex) =>
        logger.warn(s"Failed to serialize response: $response", ex)
    }
  }

}
