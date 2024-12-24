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

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

import io.vertx.core.{Future => VertxFuture}

import org.alephium.api.ApiModelCodec
import org.alephium.api.model.{BlockAndEvents, ContractEvent, TransactionTemplate}
import org.alephium.app.ws.WsParams.WsSubscriptionParams
import org.alephium.app.ws.WsRequest.Correlation
import org.alephium.crypto.Sha256
import org.alephium.json.Json._
import org.alephium.protocol.model.Address
import org.alephium.protocol.vm.LockupScript
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util.{AVector, EitherF}

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
  sealed trait WsSubscriptionParams extends WsParams {
    def subscriptionId: WsSubscriptionId
  }
  sealed trait WsNotificationParams extends WsParams {
    def subscription: WsSubscriptionId
    protected[ws] def asJsonRpcNotification: ujson.Obj = {
      ujson
        .Obj(
          "method" -> WsMethod.SubscriptionMethod,
          "params" -> writeJs(this)
        )
    }
  }
  object WsNotificationParams extends ApiModelCodec {
    implicit val wsSubscriptionParamsWriter: Writer[WsNotificationParams] = {
      implicit val blockSubscriptionWriter: Writer[WsBlockNotificationParams]       = macroW
      implicit val txSubscriptionWriter: Writer[WsTxNotificationParams]             = macroW
      implicit val contractSubscriptionWriter: Writer[WsContractNotificationParams] = macroW

      writer[ujson.Value].comap[WsNotificationParams] {
        case block: WsBlockNotificationParams       => writeJs(block)
        case tx: WsTxNotificationParams             => writeJs(tx)
        case contract: WsContractNotificationParams => writeJs(contract)
      }
    }
  }

  private type WsEventType = String
  type WsId                = String
  type WsCorrelationId     = Long
  type WsSubscriptionId    = String

  final case class SimpleSubscribeParams(eventType: WsEventType) extends WsSubscriptionParams {
    def subscriptionId: WsSubscriptionId = Sha256.hash(eventType).toHexString
  }
  object SimpleSubscribeParams {

    private val BlockEvent: WsEventType = "block"
    private val TxEvent: WsEventType    = "tx"

    protected[ws] val eventTypes: AVector[WsEventType] = AVector(BlockEvent, TxEvent)

    protected[ws] val Block: SimpleSubscribeParams = SimpleSubscribeParams(BlockEvent)
    protected[ws] val Tx: SimpleSubscribeParams    = SimpleSubscribeParams(TxEvent)

    protected[ws] def read(json: ujson.Value): Either[Error, WsSubscriptionParams] = {
      json match {
        case ujson.Str(eventTypeOrSubscriptionId) =>
          eventTypeOrSubscriptionId match {
            case BlockEvent | TxEvent => Right(SimpleSubscribeParams(eventTypeOrSubscriptionId))
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
  final case class ContractEventsSubscribeParams(
      eventType: WsEventType,
      eventIndex: Int,
      addresses: AVector[Address.Contract]
  ) extends WsSubscriptionParams {
    def subscriptionId: WsSubscriptionId =
      Sha256
        .hash(
          addresses
            .map(address => s"$eventType/$eventIndex/$address")
            .mkString(",")
        )
        .toHexString
  }
  object ContractEventsSubscribeParams {
    val Contract: WsEventType            = "contract"
    val eventTypes: AVector[WsEventType] = AVector(Contract)

    protected[ws] def from(
        eventIndex: Int,
        address: AVector[Address.Contract]
    ): ContractEventsSubscribeParams = ContractEventsSubscribeParams(Contract, eventIndex, address)

    protected[ws] def read(
        eventTypeJson: ujson.Value,
        eventIndexJson: ujson.Value,
        addressesJson: ujson.Value
    ): Either[Error, WsSubscriptionParams] = {
      (eventTypeJson, eventIndexJson, addressesJson) match {
        case (ujson.Str(eventType), ujson.Num(eventIndex), ujson.Arr(addressArr)) =>
          eventType match {
            case Contract =>
              EitherF
                .foldTry(addressArr, mutable.ArrayBuffer.empty[Address.Contract]) {
                  case (addresses, address) =>
                    LockupScript
                      .p2c(address.str)
                      .map(Address.Contract(_)) match {
                      case Some(address) => Right(addresses :+ address)
                      case None          => Left(invalidContractAddress(address.str))
                    }
                }
                .map(addresses =>
                  ContractEventsSubscribeParams(
                    eventType,
                    eventIndex.toInt,
                    AVector.from(addresses)
                  )
                )
            case unknown => Left(invalidContractParamsError(unknown))
          }
        case unsupported => Left(invalidContractParamsJsonError(unsupported))
      }
    }

    private def invalidContractAddress(address: String): Error =
      Error(Error.InvalidParamsCode, s"Contract address $address is not valid")

    private def invalidContractParamsError(eventType: WsEventType): Error =
      Error(
        Error.InvalidParamsCode,
        s"Invalid event type: $eventType, expected array with eventType, eventIndex and array of addresses"
      )
    private def invalidContractParamsJsonError(
        json: (ujson.Value, ujson.Value, ujson.Value)
    ): Error =
      Error(
        Error.InvalidParamsCode,
        s"Invalid event type json: $json, expected array with eventType, eventIndex and array of addresses"
      )

  }

  final case class UnsubscribeParams(subscriptionId: WsSubscriptionId) extends WsSubscriptionParams
  object UnsubscribeParams {
    protected[ws] def read(json: ujson.Value): Either[Error, WsSubscriptionParams] = {
      json match {
        case ujson.Str(eventTypeOrSubscriptionId) if eventTypeOrSubscriptionId.nonEmpty =>
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

  final case class WsBlockNotificationParams(
      subscription: WsSubscriptionId,
      result: BlockAndEvents
  ) extends WsNotificationParams

  object WsBlockNotificationParams {
    protected[ws] def from(blockAndEvents: BlockAndEvents): WsBlockNotificationParams =
      WsBlockNotificationParams(
        SimpleSubscribeParams.Block.subscriptionId,
        blockAndEvents
      )
  }

  final case class WsTxNotificationParams(
      subscription: WsSubscriptionId,
      result: TransactionTemplate
  ) extends WsNotificationParams

  object WsTxNotificationParams {
    protected[ws] def from(txTemplate: TransactionTemplate): WsTxNotificationParams =
      WsTxNotificationParams(
        SimpleSubscribeParams.Tx.subscriptionId,
        txTemplate
      )
  }

  final case class WsContractNotificationParams(
      subscription: WsSubscriptionId,
      result: ContractEvent
  ) extends WsNotificationParams

}

final protected[ws] case class WsRequest(id: Correlation, params: WsSubscriptionParams)
protected[ws] object WsRequest extends ApiModelCodec {
  import WsParams._

  final case class Correlation(id: WsCorrelationId) extends WithId

  implicit protected[ws] val wsRequestReader: Reader[WsRequest] =
    reader[ujson.Value].map[WsRequest] { json =>
      fromJsonString(json.render()) match {
        case Right(wsRequest) => wsRequest
        case Left(error)      => throw new IllegalArgumentException(error.message)
      }
    }
  implicit protected[ws] val wsRequestWriter: Writer[WsRequest] = writer[Request].comap[WsRequest] {
    req =>
      req.params match {
        case SimpleSubscribeParams(eventType) =>
          Request(WsMethod.SubscribeMethod, ujson.Arr(ujson.Str(eventType)), req.id.id)
        case UnsubscribeParams(subscriptionId) =>
          Request(
            WsMethod.UnsubscribeMethod,
            ujson.Arr(ujson.Str(subscriptionId)),
            req.id.id
          )
        case ContractEventsSubscribeParams(eventType, eventIndex, addresses) =>
          Request(
            WsMethod.SubscribeMethod,
            ujson.Arr(
              ujson.Str(eventType),
              ujson.Num(eventIndex.toDouble),
              ujson.Arr.from(addresses.map(address => ujson.Str(address.toBase58)))
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
            case WsMethod.SubscribeMethod   => SimpleSubscribeParams.read(arr(0))
            case WsMethod.UnsubscribeMethod => UnsubscribeParams.read(arr(0))
          }
        case ujson.Arr(arr) if arr.length == 3 =>
          ContractEventsSubscribeParams.read(arr(0), arr(1), arr(2))
        case unsupported =>
          Left(
            Error(
              Error.InvalidParamsCode,
              s"Invalid params format: $unsupported, expected array of size 1 for block/tx notifications or 3 for contract events"
            )
          )
      }
    readParams.map(params => WsRequest(Correlation(r.id), params))
  }

  protected[ws] def fromJsonString(msg: String): Either[Error, WsRequest] = {
    Try(read[RequestUnsafe](msg)) match {
      case Success(r)  => fromJsonRpc(r)
      case Failure(ex) => Left(Error(Error.ParseErrorCode, ex.getMessage))
    }
  }

  protected[ws] def subscribe(id: WsCorrelationId, params: WsSubscriptionParams): WsRequest =
    WsRequest(Correlation(id), params)

  protected[ws] def unsubscribe(id: WsCorrelationId, subscriptionId: WsSubscriptionId): WsRequest =
    WsRequest(Correlation(id), UnsubscribeParams(subscriptionId))
}

object WsUtils {
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
