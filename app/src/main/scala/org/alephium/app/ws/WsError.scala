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

import org.alephium.app.ws.WsParams.ContractEventsSubscribeParams.ContractEvent
import org.alephium.app.ws.WsParams.SimpleSubscribeParams.{BlockEvent, TxEvent}
import org.alephium.app.ws.WsParams.WsSubscriptionId
import org.alephium.rpc.model.JsonRPC.Error

object WsError {
  private val AlreadySubscribed: Int         = -32010
  private val AlreadyUnSubscribed: Int       = -32011
  private val SubscriptionLimitExceeded: Int = -32012

  private val ContractParamsObject = "{address: [String], eventIndex?: Integer}"

  protected[ws] def invalidUnsubscriptionFormat(json: ujson.Value): Error =
    Error(
      Error.InvalidParamsCode,
      s"Invalid unsubscription format: $json, expected array with hex encoded 256bit hash subscriptionId"
    )

  protected[ws] def invalidSubscriptionId(subscriptionId: String): Error =
    Error(
      Error.InvalidParamsCode,
      s"Invalid subscriptionId: $subscriptionId, it should be hex encoded 256bit hash"
    )

  protected[ws] def invalidContractAddress(address: String): Error =
    Error(
      Error.InvalidParamsCode,
      s"Contract address $address is not valid Base58 encoded P2C script"
    )

  protected[ws] def emptyContractAddress: Error =
    Error(
      Error.InvalidParamsCode,
      "Contract address array cannot be empty, define at least one contract address"
    )

  protected[ws] def tooManyContractAddresses(limit: Int): Error =
    Error(
      Error.InvalidParamsCode,
      s"Contract address array cannot be greater than $limit"
    )

  protected[ws] def duplicatedAddresses(duplicateAddress: String): Error =
    Error(
      Error.InvalidParamsCode,
      s"Contract address array cannot contain duplicate address: $duplicateAddress"
    )

  protected[ws] def invalidContractAddressType: Error =
    Error(Error.InvalidParamsCode, s"Contract address should be base58 encoded String")

  protected[ws] def invalidParamsFormat(json: ujson.Value): Error =
    Error(
      Error.InvalidParamsCode,
      s"Invalid params format: $json. Expected an array of size 1: [$BlockEvent | $TxEvent], or size 2: [$ContractEvent, $ContractParamsObject]"
    )

  protected[ws] def invalidContractParamsFormat(json: ujson.Obj): Error =
    Error(
      Error.InvalidParamsCode,
      s"Invalid contract params object: $json, expected: $ContractParamsObject"
    )

  protected[ws] def invalidContractParamsEventIndexType(json: ujson.Value): Error =
    Error(
      Error.InvalidParamsCode,
      s"Invalid contract params eventIndex field type: $json, expected integer"
    )

  protected[ws] def alreadySubscribed(subscriptionId: WsSubscriptionId): Error =
    Error(WsError.AlreadySubscribed, subscriptionId.toHexString)

  protected[ws] def alreadyUnSubscribed(subscriptionId: WsSubscriptionId): Error =
    Error(WsError.AlreadyUnSubscribed, subscriptionId.toHexString)

  protected[ws] def subscriptionLimitExceeded(limit: Int): Error =
    Error(WsError.SubscriptionLimitExceeded, s"Number of subscriptions is limited to $limit")
}
