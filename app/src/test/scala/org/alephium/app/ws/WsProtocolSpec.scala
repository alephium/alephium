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

import org.alephium.api.model.{
  BlockAndEvents,
  BlockEntry,
  ContractEventByBlockHash,
  TransactionTemplate
}
import org.alephium.json.Json._
import org.alephium.protocol.vm.{LogStateRef, LogStatesId}
import org.alephium.rpc.model.JsonRPC
import org.alephium.rpc.model.JsonRPC.RequestUnsafe
import org.alephium.util.{AlephiumSpec, AVector, TimeStamp}
import org.alephium.ws._
import org.alephium.ws.WsParams._
import org.alephium.ws.WsParams.ContractEventsSubscribeParams.{AddressesField, EventIndexField}

class WsProtocolSpec extends AlephiumSpec with WsSubscriptionFixture {

  private val contractAddressLimit = config.network.wsMaxContractEventAddresses

  "WsProtocol" should "refuse all WsRequests that are not JsonRPC compliant" in {
    val method    = WsMethod.SubscribeMethod
    val eventType = "foo"
    WsRequest
      .fromJsonString(s"""{"method": "$method", "params": ["$eventType"]}""", contractAddressLimit)
      .isLeft is true
    WsRequest.fromJsonString(s"""{"method": "$method"""", contractAddressLimit).isLeft is true
    WsRequest
      .fromJsonString(s"""{"method2": "$method", "params": ["$eventType"]}""", contractAddressLimit)
      .isLeft is true
    WsRequest
      .fromJsonString(s"""{"method": "$method", "params2": ["$eventType"]}""", contractAddressLimit)
      .isLeft is true
    WsRequest
      .fromJsonString(s"""{"method": "$method", "params": "$eventType"}""", contractAddressLimit)
      .isLeft is true
    WsRequest
      .fromJsonString(
        s"""{"jsonrpc": "2.0", "method": "$method", "params": "$eventType"}""",
        contractAddressLimit
      )
      .isLeft is true
    WsRequest
      .fromJsonString(
        s"""{"id": 0, "method": "$method", "params": "$eventType"}""",
        contractAddressLimit
      )
      .isLeft is true
  }

  "ContractEventsSubscribeParams" should "build subscriptionId regardless of address order" in {
    val sId_1 = ContractEventsSubscribeParams(
      AVector(contractAddress_0, contractAddress_1),
      None
    ).subscriptionId
    val sId_2 = ContractEventsSubscribeParams(
      AVector(contractAddress_1, contractAddress_0),
      None
    ).subscriptionId
    sId_1 is sId_2
  }

  "ContractEventsSubscribeParams" should "build unique contract addresses or fail" in {
    ContractEventsSubscribeParams
      .buildUniqueContractAddresses(ujson.Arr(ujson.Str("")).arr)
      .isLeft is true
    ContractEventsSubscribeParams
      .buildUniqueContractAddresses(ujson.Arr(ujson.Str(contractAddress_0.toBase58)).arr)
      .isRight is true
    ContractEventsSubscribeParams.buildUniqueContractAddresses(
      ujson.Arr(ujson.Str(contractAddress_0.toBase58), ujson.Str(contractAddress_1.toBase58)).arr
    ) match {
      case Right(addresses) =>
        addresses.length is 2
        addresses.contains(contractAddress_0)
        addresses.contains(contractAddress_1)
      case Left(_) => fail("Should return Right for valid addresses")
    }

    ContractEventsSubscribeParams.buildUniqueContractAddresses(
      ujson.Arr(ujson.Str(contractAddress_0.toBase58), ujson.Str("invalid-address")).arr
    ) match {
      case Right(_)    => fail("Should return Left for invalid address")
      case Left(error) => error is WsError.invalidContractAddress("invalid-address")
    }

    val duplicateAddresses = ujson
      .Arr(
        ujson.Str(contractAddress_0.toBase58),
        ujson.Str(contractAddress_1.toBase58),
        ujson.Str(contractAddress_0.toBase58)
      )
      .arr
    ContractEventsSubscribeParams.buildUniqueContractAddresses(duplicateAddresses) match {
      case Right(_)    => fail("Should return Left for duplicate addresses")
      case Left(error) => error is WsError.duplicatedAddresses(contractAddress_0.toBase58)
    }
  }

  "WsRequest" should "pass ser/deser round-trip for simple subscription/unsubscription" in {
    AVector(SimpleSubscribeParams.Block, SimpleSubscribeParams.Tx).foreach { params =>
      val validSubscriptionReqJson =
        ujson.Obj(
          "method"  -> WsMethod.SubscribeMethod,
          "params"  -> ujson.Arr(params.eventType),
          "id"      -> 0,
          "jsonrpc" -> "2.0"
        )
      val subscribeRequest = WsRequest.subscribe(0, params)
      writeJs(subscribeRequest) is validSubscriptionReqJson
      subscribeRequest is read[WsRequest](validSubscriptionReqJson)

      val validUnSubscriptionReqJson =
        ujson.Obj(
          "method"  -> WsMethod.UnsubscribeMethod,
          "params"  -> ujson.Arr(params.subscriptionId.toHexString),
          "id"      -> 0,
          "jsonrpc" -> "2.0"
        )
      val unSubscribeRequest = WsRequest.unsubscribe(0, params.subscriptionId)
      writeJs(unSubscribeRequest) is validUnSubscriptionReqJson
      unSubscribeRequest is read[WsRequest](validUnSubscriptionReqJson)

      val jsonRpcSubscribeRequest =
        RequestUnsafe(
          "2.0",
          WsMethod.SubscribeMethod,
          ujson.Arr(ujson.Str(params.eventType)),
          0
        )
      val expectedSubscribeRequest =
        WsRequest.fromJsonRpc(jsonRpcSubscribeRequest, contractAddressLimit).rightValue
      expectedSubscribeRequest is read[WsRequest](write(expectedSubscribeRequest))

      val jsonRpcUnSubscribeRequest =
        RequestUnsafe(
          "2.0",
          WsMethod.UnsubscribeMethod,
          ujson.Arr(ujson.Str(params.subscriptionId.toHexString)),
          0
        )
      val expectedUnSubscribeRequest =
        WsRequest.fromJsonRpc(jsonRpcUnSubscribeRequest, contractAddressLimit).rightValue
      expectedUnSubscribeRequest is read[WsRequest](write(expectedUnSubscribeRequest))
    }
  }

  "WsRequest" should "pass ser/deser round-trip for contract event subscription" in {
    val eventIndex = ujson.Num(EventIndex_0.toDouble)
    val addresses  = ujson.Arr(ujson.Str(contractAddress_0.toBase58))

    AVector(
      Some(EventIndex_0) -> ujson.Obj(AddressesField -> addresses, EventIndexField -> eventIndex),
      None               -> ujson.Obj(AddressesField -> addresses)
    ).foreach { case (eventIndexOpt, expectedObj) =>
      val contractEventParams =
        ContractEventsSubscribeParams.fromSingle(contractAddress_0, eventIndexOpt)

      val validSubscriptionReqJson = ujson
        .Obj(
          "method"  -> WsMethod.SubscribeMethod,
          "params"  -> ujson.Arr(ContractEventsSubscribeParams.ContractEvent, expectedObj),
          "id"      -> 0,
          "jsonrpc" -> "2.0"
        )
      val subscribeRequest = WsRequest.subscribe(0, contractEventParams)
      writeJs(subscribeRequest) is validSubscriptionReqJson
      subscribeRequest is read[WsRequest](validSubscriptionReqJson)

      val eventType = ujson.Str(ContractEventsSubscribeParams.ContractEvent)

      val jsonRpcSubscribeRequest =
        RequestUnsafe("2.0", WsMethod.SubscribeMethod, ujson.Arr(eventType, expectedObj), 0)
      val expectedSubscribeRequest =
        WsRequest.fromJsonRpc(jsonRpcSubscribeRequest, contractAddressLimit).rightValue
      expectedSubscribeRequest is read[WsRequest](write(expectedSubscribeRequest))
    }
  }

  "WsRequest" should "pass ser/deser round-trip for contract event unsubscription" in {
    val contractEventParams =
      ContractEventsSubscribeParams.fromSingle(contractAddress_0, Some(EventIndex_0))

    val validUnSubscriptionReqJson = ujson
      .Obj(
        "method"  -> WsMethod.UnsubscribeMethod,
        "params"  -> ujson.Arr(contractEventParams.subscriptionId.toHexString),
        "id"      -> 0,
        "jsonrpc" -> "2.0"
      )
    val unSubscribeRequest = WsRequest.unsubscribe(0, contractEventParams.subscriptionId)
    writeJs(unSubscribeRequest) is validUnSubscriptionReqJson
    unSubscribeRequest is read[WsRequest](validUnSubscriptionReqJson)

    val jsonRpcUnSubscribeRequest =
      RequestUnsafe(
        "2.0",
        WsMethod.UnsubscribeMethod,
        ujson.Arr(
          ujson.Str(
            ContractEventsSubscribeParams
              .fromSingle(contractAddress_0, Some(EventIndex_0))
              .subscriptionId
              .toHexString
          )
        ),
        0
      )
    val expectedUnSubscribeRequest =
      WsRequest.fromJsonRpc(jsonRpcUnSubscribeRequest, contractAddressLimit).rightValue
    expectedUnSubscribeRequest is read[WsRequest](write(expectedUnSubscribeRequest))
  }

  "WsRequest" should "not allow for building contract event subscription with invalid contract addresses" in {
    val eventType  = ujson.Str(ContractEventsSubscribeParams.ContractEvent)
    val eventIndex = ujson.Num(EventIndex_0.toDouble)
    val invalidSubscriptionRequest =
      ujson
        .Obj(
          "method" -> WsMethod.SubscribeMethod,
          "params" -> ujson.Arr(
            ContractEventsSubscribeParams.ContractEvent,
            ujson.Obj(
              EventIndexField -> eventIndex,
              AddressesField  -> ujson.Arr()
            )
          ),
          "id"      -> 0,
          "jsonrpc" -> "2.0"
        )
    assertThrows[JsonRPC.Error](read[WsRequest](invalidSubscriptionRequest))

    val requestWithEmptyAddresses =
      RequestUnsafe(
        "2.0",
        WsMethod.SubscribeMethod,
        ujson.Arr(
          eventType,
          ujson.Obj(
            EventIndexField -> eventIndex,
            AddressesField  -> ujson.Arr()
          )
        ),
        0
      )
    WsRequest
      .fromJsonRpc(requestWithEmptyAddresses, contractAddressLimit)
      .isLeft is true

    val requestWithDuplicateAddresses =
      RequestUnsafe(
        "2.0",
        WsMethod.SubscribeMethod,
        ujson.Arr(
          eventType,
          ujson.Obj(
            EventIndexField -> eventIndex,
            AddressesField  -> ujson.Arr(duplicateAddresses.map(_.toBase58))
          )
        ),
        0
      )
    WsRequest
      .fromJsonRpc(requestWithDuplicateAddresses, contractAddressLimit)
      .isLeft is true

    val requestWithTooManyAddresses =
      RequestUnsafe(
        "2.0",
        WsMethod.SubscribeMethod,
        ujson.Arr(
          eventType,
          eventIndex,
          ujson.Obj(
            EventIndexField -> eventIndex,
            AddressesField  -> ujson.Arr(tooManyContractAddresses.map(_.toBase58))
          )
        ),
        0
      )
    WsRequest
      .fromJsonRpc(requestWithTooManyAddresses, contractAddressLimit)
      .isLeft is true

    val requestWithInvalidEventIndex =
      RequestUnsafe(
        "2.0",
        WsMethod.SubscribeMethod,
        ujson.Arr(
          eventType,
          ujson.Obj(
            EventIndexField -> "2",
            AddressesField  -> ujson.Arr(ujson.Str(contractAddress_0.toBase58))
          )
        ),
        0
      )
    WsRequest
      .fromJsonRpc(requestWithInvalidEventIndex, contractAddressLimit)
      .isLeft is true
  }

  "WsNotificationParams" should "pass ser/deser round-trip for notifications" in {
    val blockAndEvents =
      BlockAndEvents(
        BlockEntry.from(dummyBlock, 0).rightValue,
        logStatesFor(AVector(contractAddress_0.contractId -> EventIndex_0)).map {
          case (contractId, logState) =>
            ContractEventByBlockHash.from(LogStateRef(LogStatesId(contractId, 0), 0), logState)
        }
      )

    val blockNotificationParams: WsNotificationParams =
      WsBlockNotificationParams.from(blockAndEvents)
    val blockNotificationJson = write(blockNotificationParams)
    read[WsNotificationParams](blockNotificationJson) is blockNotificationParams

    val blockNotificationRpc = blockNotificationParams.asJsonRpcNotification
    blockNotificationRpc("method") is WsMethod.SubscriptionMethod
    blockNotificationRpc("params") is writeJs(blockNotificationParams)

    val txNotificationParams: WsNotificationParams =
      WsTxNotificationParams.from(
        TransactionTemplate.fromProtocol(transactionGen().sample.get.toTemplate, TimeStamp.now())
      )
    val txNotificationJson = write(txNotificationParams)
    read[WsNotificationParams](txNotificationJson) is txNotificationParams

    val txNotificationRpc = txNotificationParams.asJsonRpcNotification
    txNotificationRpc("method") is WsMethod.SubscriptionMethod
    txNotificationRpc("params") is writeJs(txNotificationParams)

    val contractEventParams =
      ContractEventsSubscribeParams.fromSingle(contractAddress_0, Some(EventIndex_0))
    val contractEventNotificationParams: WsNotificationParams =
      WsContractEventNotificationParams(contractEventParams.subscriptionId, contractEvent)
    val contractEventNotificationJson = write(contractEventNotificationParams)
    read[WsNotificationParams](contractEventNotificationJson) is contractEventNotificationParams

    val contractEventNotificationRpc = contractEventNotificationParams.asJsonRpcNotification
    contractEventNotificationRpc("method") is WsMethod.SubscriptionMethod
    contractEventNotificationRpc("params") is writeJs(contractEventNotificationParams)

    val allNotifications =
      AVector(blockNotificationParams, txNotificationParams, contractEventNotificationParams)
    allNotifications.foreach { notification =>
      val serialized   = write(notification)(WsNotificationParams.wsSubscriptionParamsWriter)
      val deserialized = read[WsNotificationParams](serialized)
      deserialized is notification
    }
  }
}
