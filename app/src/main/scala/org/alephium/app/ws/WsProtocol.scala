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

package org.alephium.app.ws

import scala.collection.immutable.{SortedMap, TreeMap}
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

import io.vertx.core.{Future => VertxFuture}

import org.alephium.api.ApiModelCodec
import org.alephium.api.model.BlockEntry
import org.alephium.app.ws.WsParams.WsSubscriptionParams
import org.alephium.app.ws.WsRequest.Correlation
import org.alephium.crypto.Sha256
import org.alephium.json.Json._
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util.AVector

protected[ws] object WsError {
  val AlreadySubscribed: Int   = -32010
  val AlreadyUnsubscribed: Int = -32011
}

protected[ws] object WsMethod {
  private type WsMethodType = String
  val SubscribeMethod: WsMethodType    = "subscribe"
  val UnsubscribeMethod: WsMethodType  = "unsubscribe"
  val SubscriptionMethod: WsMethodType = "subscription"
}

protected[ws] object WsParams {
  sealed trait WsParams
  sealed trait WsSubscriptionParams extends WsParams
  private type WsEventType = String

  type WsId             = String
  type WsCorrelationId  = Long
  type WsSubscriptionId = String

  final case class SubscribeParams(eventType: WsEventType) extends WsSubscriptionParams {
    def subscriptionId: WsSubscriptionId = Sha256.hash(eventType).toHexString
  }
  object SubscribeParams {

    val BlockEvent: WsEventType = "block"
    val TxEvent: WsEventType    = "tx"

    val eventTypes: AVector[WsEventType] = AVector(BlockEvent, TxEvent)

    val Block: SubscribeParams = SubscribeParams(BlockEvent)
    val Tx: SubscribeParams    = SubscribeParams(TxEvent)

    def read(json: ujson.Value): Either[Error, WsSubscriptionParams] = {
      json match {
        case ujson.Str(eventTypeOrSubscriptionId) =>
          eventTypeOrSubscriptionId match {
            case BlockEvent | TxEvent => Right(SubscribeParams(eventTypeOrSubscriptionId))
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
  final case class FilteredSubscribeParams(
      eventType: WsEventType,
      filter: SortedMap[String, String]
  ) extends WsSubscriptionParams {
    def subscriptionId: String = Sha256
      .hash(eventType + "?" + filter.map { case (k, v) => s"$k=$v" }.mkString("&"))
      .toHexString
  }
  object FilteredSubscribeParams {
    val Contract: WsEventType            = "contract"
    val eventTypes: AVector[WsEventType] = AVector(Contract)
    def read(
        eventTypeJson: ujson.Value,
        filterJson: ujson.Value
    ): Either[Error, WsSubscriptionParams] = {
      (eventTypeJson, filterJson) match {
        case (ujson.Str(eventType), ujson.Obj(filter)) =>
          eventType match {
            case Contract =>
              val sortedFilter = TreeMap(filter.toSeq.map { case (k, v) => (k, v.str) }*)
              Right(FilteredSubscribeParams(eventType, sortedFilter))
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

  final case class UnsubscribeParams(subscriptionId: WsSubscriptionId) extends WsSubscriptionParams
  object UnsubscribeParams {
    def read(json: ujson.Value): Either[Error, WsSubscriptionParams] = {
      json match {
        case ujson.Str(eventTypeOrSubscriptionId) =>
          Right(UnsubscribeParams(eventTypeOrSubscriptionId))
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

  final case class WsNotificationParams(subscription: WsSubscriptionId, result: BlockEntry)
      extends WsParams
  object WsNotificationParams extends ApiModelCodec {
    // macroW fails : [wartremover:ToString] trait CharSequence does not override toString and automatic toString is disabled
    implicit val wsNotificationParamsWriter: Writer[WsNotificationParams] =
      writer[ujson.Value].comap[WsNotificationParams] {
        case WsNotificationParams(subscription, result) =>
          ujson.Obj(
            "subscription" -> ujson.Str(subscription),
            "result"       -> write(result)
          )
      }
  }
}

final protected[ws] case class WsRequest(id: Correlation, params: WsSubscriptionParams)
protected[ws] object WsRequest extends ApiModelCodec {
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
      case SubscribeParams(eventType) =>
        Request(WsMethod.SubscribeMethod, ujson.Arr(ujson.Str(eventType)), req.id.id)
      case UnsubscribeParams(subscriptionId) =>
        Request(WsMethod.UnsubscribeMethod, ujson.Arr(ujson.Str(subscriptionId)), req.id.id)
      case FilteredSubscribeParams(eventType, filter) =>
        Request(
          WsMethod.SubscribeMethod,
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
            case WsMethod.SubscribeMethod   => SubscribeParams.read(arr(0))
            case WsMethod.UnsubscribeMethod => UnsubscribeParams.read(arr(0))
          }
        case ujson.Arr(arr) if arr.length == 2 => FilteredSubscribeParams.read(arr(0), arr(1))
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

  def subscribe(id: WsCorrelationId, subscription: SubscribeParams): WsRequest =
    WsRequest(Correlation(id), subscription)

  def unsubscribe(id: WsCorrelationId, subscriptionId: WsSubscriptionId): WsRequest =
    WsRequest(Correlation(id), UnsubscribeParams(subscriptionId))

  def subscribeWithFilter(id: WsCorrelationId, subscription: FilteredSubscribeParams): WsRequest =
    WsRequest(Correlation(id), subscription)
}

protected[ws] object WsUtils {
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

}
