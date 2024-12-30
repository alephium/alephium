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

import scala.concurrent.{Future, Promise}

import io.vertx.core.{Future => VertxFuture}

import org.alephium.app.ws.WsParams.{ContractEventsSubscribeParams, WsEventType, WsSubscriptionId}
import org.alephium.app.ws.WsParams.SimpleSubscribeParams.eventTypes
import org.alephium.rpc.model.JsonRPC.Error

object WsError {
  protected[ws] val AlreadySubscribed: Int   = -32010
  protected[ws] val AlreadyUnSubscribed: Int = -32011

  protected[ws] def invalidEventType(eventType: WsEventType): Error =
    Error(
      Error.InvalidParamsCode,
      s"Invalid event type: $eventType, expected array with one of: ${eventTypes.mkString(", ")}"
    )

  protected[ws] def invalidSubscriptionEventTypes(json: ujson.Value): Error =
    Error(
      Error.InvalidParamsCode,
      s"Invalid subscription: $json, expected array with one of: ${eventTypes.mkString(", ")}"
    )

  protected[ws] def invalidUnsubscriptionFormat(json: ujson.Value): Error =
    Error(
      Error.InvalidParamsCode,
      s"Invalid subscription: $json, expected array with subscriptionId"
    )

  protected[ws] def invalidSubscriptionId(subscriptionId: String): Error =
    Error(
      Error.InvalidParamsCode,
      s"Invalid subscription ID: $subscriptionId, it should be SHA256 hash as Hex"
    )

  protected[ws] def invalidContractEventParams(eventType: WsEventType): Error =
    Error(
      Error.InvalidParamsCode,
      s"Unknown event type: $eventType, expected: ${ContractEventsSubscribeParams.Contract}"
    )

  protected[ws] def invalidParamsArrayElements(
      json: (ujson.Value, ujson.Value, ujson.Value)
  ): Error =
    Error(
      Error.InvalidParamsCode,
      s"Invalid params: $json, expected array with eventType, eventIndex and array of addresses"
    )

  protected[ws] def invalidContractAddress(address: String): Error =
    Error(Error.InvalidParamsCode, s"Contract address $address is not valid")

  protected[ws] def invalidContractAddressType: Error =
    Error(Error.InvalidParamsCode, s"Contract address should be base58 encoded String")

  protected[ws] def invalidParamsFormat(json: ujson.Value): Error =
    Error(
      Error.InvalidParamsCode,
      s"Invalid params format: $json, expected array of size 1 for block/tx or 3 for contract events notifications"
    )

  protected[ws] def alreadySubscribed(subscriptionId: WsSubscriptionId): Error =
    Error(WsError.AlreadySubscribed, subscriptionId.toHexString)

  protected[ws] def alreadyUnSubscribed(subscriptionId: WsSubscriptionId): Error =
    Error(WsError.AlreadyUnSubscribed, subscriptionId.toHexString)
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
