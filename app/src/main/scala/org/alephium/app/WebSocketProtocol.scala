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

import scala.collection.immutable.{SortedMap, TreeMap}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

import com.typesafe.scalalogging.LazyLogging
import io.vertx.core.{Future => VertxFuture}
import io.vertx.core.http.ServerWebSocket

import org.alephium.api.ApiModelCodec
import org.alephium.app.WsRequest.Correlation
import org.alephium.crypto.Sha256
import org.alephium.json.Json._
import org.alephium.rpc.model.JsonRPC.{Error, Request, RequestUnsafe, Response, WithId}
import org.alephium.util.AVector

object WsProtocol {
  object Code {
    val AlreadySubscribed: Int   = -32010
    val AlreadyUnsubscribed: Int = -32011
  }
}

sealed trait WsParams
object WsParams {
  private type WsMethodType = String
  private type WsEventType  = String

  type WsId             = String
  type WsCorrelationId  = Long
  type WsSubscriptionId = String

  val methodTypes: AVector[WsMethodType] =
    AVector(Subscription.MethodType, Unsubscription.MethodType)

  final case class Subscription(eventType: WsEventType) extends WsParams {
    def subscriptionId: WsSubscriptionId = Sha256.hash(eventType).toHexString
  }
  object Subscription {
    val MethodType: WsMethodType = "subscribe"

    val BlockEvent: WsEventType = "block"
    val TxEvent: WsEventType    = "tx"

    val eventTypes: AVector[WsEventType] = AVector(BlockEvent, TxEvent)

    val Block: Subscription = Subscription(BlockEvent)
    val Tx: Subscription    = Subscription(TxEvent)

    def read(json: ujson.Value): Either[Error, WsParams] = {
      json match {
        case ujson.Str(eventTypeOrSubscriptionId) =>
          eventTypeOrSubscriptionId match {
            case BlockEvent | TxEvent => Right(Subscription(eventTypeOrSubscriptionId))
            case unknown              => Left(invalidEventTypeError(unknown))
          }
        case unsupported => Left(invalidSubscriptionJsonError(unsupported))
      }
    }
    private def invalidEventTypeError(eventType: WsEventType): Error =
      Error(
        Error.InvalidParamsCode,
        s"Invalid event type: $eventType, expected array with one of: ${eventTypes.mkString(", ")}"
      )
    private def invalidSubscriptionJsonError(json: ujson.Value): Error =
      Error(
        Error.InvalidParamsCode,
        s"Invalid subscription json: $json, expected array with one of: ${eventTypes.mkString(", ")}"
      )
  }
  final case class FilteredSubscription(eventType: WsEventType, filter: SortedMap[String, String])
      extends WsParams {
    def subscriptionId: String = Sha256
      .hash(eventType + "?" + filter.map { case (k, v) => s"$k=$v" }.mkString("&"))
      .toHexString
  }
  object FilteredSubscription {
    val Contract: WsEventType            = "contract"
    val eventTypes: AVector[WsEventType] = AVector(Contract)
    def read(eventTypeJson: ujson.Value, filterJson: ujson.Value): Either[Error, WsParams] = {
      (eventTypeJson, filterJson) match {
        case (ujson.Str(eventType), ujson.Obj(filter)) =>
          eventType match {
            case Contract =>
              val sortedFilter = TreeMap(filter.toSeq.map { case (k, v) => (k, v.str) }*)
              Right(FilteredSubscription(eventType, sortedFilter))
            case unknown => Left(invalidFilteredParamsError(unknown))
          }
        case unsupported => Left(invalidFilteredParamsJsonError(unsupported))
      }
    }
    private def invalidFilteredParamsError(eventType: WsEventType): Error =
      Error(
        Error.InvalidParamsCode,
        s"Invalid event type: $eventType, expected array with one of: ${eventTypes.mkString(", ")} and filter object"
      )
    private def invalidFilteredParamsJsonError(json: (ujson.Value, ujson.Value)): Error =
      Error(
        Error.InvalidParamsCode,
        s"Invalid event type json: $json, expected array with one of: ${eventTypes.mkString(", ")} and filter object"
      )

  }

  final case class Unsubscription(subscriptionId: WsSubscriptionId) extends WsParams
  object Unsubscription {
    val MethodType: WsMethodType = "unsubscribe"
    def read(json: ujson.Value): Either[Error, WsParams] = {
      json match {
        case ujson.Str(eventTypeOrSubscriptionId) =>
          Right(Unsubscription(eventTypeOrSubscriptionId))
        case unsupported =>
          Left(invalidUnsubscriptionJsonError(unsupported))
      }
    }
    private def invalidUnsubscriptionJsonError(json: ujson.Value): Error =
      Error(
        Error.InvalidParamsCode,
        s"Invalid subscription json: $json, expected array with subscriptionId"
      )
  }
}

final case class WsRequest(id: Correlation, params: WsParams)

object WsRequest extends ApiModelCodec {
  import WsParams._

  final case class Correlation(id: WsCorrelationId) extends WithId

  implicit val wsRequestReader: Reader[WsRequest] = reader[ujson.Value].map[WsRequest] { json =>
    fromJsonString(json.render()) match {
      case Right(wsRequest) => wsRequest
      case Left(error)      => throw new IllegalArgumentException(error.message)
    }
  }
  implicit val wsRequestWriter: Writer[WsRequest] = writer[Request].comap[WsRequest] { req =>
    req.params match {
      case Subscription(eventType) =>
        Request(Subscription.MethodType, ujson.Arr(ujson.Str(eventType)), req.id.id)
      case Unsubscription(subscriptionId) =>
        Request(Unsubscription.MethodType, ujson.Arr(ujson.Str(subscriptionId)), req.id.id)
      case FilteredSubscription(eventType, filter) =>
        Request(
          Subscription.MethodType,
          ujson.Arr(
            ujson.Str(eventType),
            ujson.Obj.from(filter.map { case (k, v) => (k, ujson.Str(v)) })
          ),
          req.id.id
        )
    }
  }

  private def fromJsonRpc(r: RequestUnsafe): Either[Error, WsRequest] = {
    def readParams =
      r.params match {
        case ujson.Arr(arr) if arr.length == 1 =>
          r.method match {
            case Subscription.MethodType   => Subscription.read(arr(0))
            case Unsubscription.MethodType => Unsubscription.read(arr(0))
          }
        case ujson.Arr(arr) if arr.length == 2 => FilteredSubscription.read(arr(0), arr(1))
        case unsupported =>
          Left(
            Error(
              Error.InvalidParamsCode,
              s"Invalid params format: $unsupported, expected array of size 1 or 2"
            )
          )
      }
    readParams.map(params => WsRequest(Correlation(r.id), params))
  }

  def fromJsonString(msg: String): Either[Error, WsRequest] = {
    Try(read[RequestUnsafe](msg)) match {
      case Success(r)  => fromJsonRpc(r)
      case Failure(ex) => Left(Error(Error.ParseErrorCode, ex.getMessage))
    }
  }

  def subscribe(id: WsCorrelationId, subscription: Subscription): WsRequest =
    WsRequest(Correlation(id), subscription)

  def unsubscribe(id: WsCorrelationId, subscriptionId: WsSubscriptionId): WsRequest =
    WsRequest(Correlation(id), Unsubscription(subscriptionId))

  def subscribeWithFilter(id: WsCorrelationId, subscription: FilteredSubscription): WsRequest =
    WsRequest(Correlation(id), subscription)
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
