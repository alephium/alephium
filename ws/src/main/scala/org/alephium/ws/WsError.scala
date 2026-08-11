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

package org.alephium.ws

import org.alephium.rpc.model.JsonRPC.Error
import org.alephium.ws.WsParams.ContractEventsSubscribeParams.{
  AddressesField,
  ContractEvent,
  EventIndexField,
  LowestContractEventIndex
}
import org.alephium.ws.WsParams.SimpleSubscribeParams.{BlockEvent, TxEvent}
import org.alephium.ws.WsParams.WsSubscriptionId

object WsError {
  private val AlreadySubscribed: Int         = -32010
  private val AlreadyUnSubscribed: Int       = -32011
  private val SubscriptionLimitExceeded: Int = -32012

  private val ContractParamsObject = s"{$AddressesField: [String], $EventIndexField?: Integer}"

  def invalidUnsubscriptionFormat(json: ujson.Value): Error =
    Error(
      Error.InvalidParamsCode,
      s"Invalid unsubscription format: $json, expected array with hex encoded 256bit hash subscriptionId"
    )

  def invalidSubscriptionId(subscriptionId: String): Error =
    Error(
      Error.InvalidParamsCode,
      s"Invalid subscriptionId: $subscriptionId, it should be hex encoded 256bit hash"
    )

  def invalidContractAddress(address: String): Error =
    Error(
      Error.InvalidParamsCode,
      s"Contract address $address is not valid Base58 encoded P2C script"
    )

  def emptyContractAddress: Error =
    Error(
      Error.InvalidParamsCode,
      "Contract address array cannot be empty, define at least one contract address"
    )

  def tooManyContractAddresses(limit: Int): Error =
    Error(
      Error.InvalidParamsCode,
      s"Contract address array cannot be greater than $limit"
    )

  def duplicatedAddresses(duplicateAddress: String): Error =
    Error(
      Error.InvalidParamsCode,
      s"Contract address array cannot contain duplicate address: $duplicateAddress"
    )

  def invalidContractAddressType: Error =
    Error(Error.InvalidParamsCode, s"Contract address should be base58 encoded String")

  def invalidParamsFormat(json: ujson.Value): Error =
    Error(
      Error.InvalidParamsCode,
      s"Invalid params format: $json. Expected an array of size 1: [$BlockEvent | $TxEvent], or size 2: [$ContractEvent, $ContractParamsObject]"
    )

  def invalidContractParamsFormat(json: ujson.Obj): Error =
    Error(
      Error.InvalidParamsCode,
      s"Invalid contract params object: $json, expected: $ContractParamsObject"
    )

  def invalidContractParamsEventIndexType(json: ujson.Value): Error =
    Error(
      Error.InvalidParamsCode,
      s"Invalid contract params eventIndex field type: $json, expected integer greater or equal than $LowestContractEventIndex"
    )

  def alreadySubscribed(subscriptionId: WsSubscriptionId): Error =
    Error(WsError.AlreadySubscribed, subscriptionId.toHexString)

  def alreadyUnSubscribed(subscriptionId: WsSubscriptionId): Error =
    Error(WsError.AlreadyUnSubscribed, subscriptionId.toHexString)

  def subscriptionLimitExceeded(limit: Int): Error =
    Error(WsError.SubscriptionLimitExceeded, s"Number of subscriptions is limited to $limit")
}
