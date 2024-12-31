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

import scala.concurrent.ExecutionContext.Implicits
import scala.util.{Failure, Success}

import io.vertx.core.{Future => VertxFuture}

import org.alephium.api.model.{BlockAndEvents, BlockEntry, TransactionTemplate}
import org.alephium.app.ws.WsParams._
import org.alephium.json.Json._
import org.alephium.rpc.model.JsonRPC
import org.alephium.rpc.model.JsonRPC.RequestUnsafe
import org.alephium.util.{AVector, TimeStamp}

class WsProtocolSpec extends WsSubscriptionFixture {

  "WsProtocol" should "refuse all WsRequests that are not JsonRPC compliant" in {
    val method    = WsMethod.SubscribeMethod
    val eventType = "foo"
    WsRequest
      .fromJsonString(s"""{"method": "$method", "params": ["$eventType"]}""")
      .isLeft is true
    WsRequest.fromJsonString(s"""{"method": "$method"""").isLeft is true
    WsRequest
      .fromJsonString(s"""{"method2": "$method", "params": ["$eventType"]}""")
      .isLeft is true
    WsRequest
      .fromJsonString(s"""{"method": "$method", "params2": ["$eventType"]}""")
      .isLeft is true
    WsRequest.fromJsonString(s"""{"method": "$method", "params": "$eventType"}""").isLeft is true
    WsRequest
      .fromJsonString(s"""{"jsonrpc": "2.0", "method": "$method", "params": "$eventType"}""")
      .isLeft is true
    WsRequest
      .fromJsonString(s"""{"id": 0, "method": "$method", "params": "$eventType"}""")
      .isLeft is true
  }

  "WsRequest" should "pass ser/deser round-trip for simple subscription/unsubscription" in {
    AVector(SimpleSubscribeParams.Block, SimpleSubscribeParams.Tx).foreach { params =>
      val validSubscriptionReqJson =
        s"""{"method":"${WsMethod.SubscribeMethod}","params":["${params.eventType}"],"id":0,"jsonrpc":"2.0"}"""
      val subscribeRequest = WsRequest.subscribe(0, params)
      write(subscribeRequest) is validSubscriptionReqJson
      subscribeRequest is read[WsRequest](validSubscriptionReqJson)

      val validUnSubscriptionReqJson =
        s"""{"method":"${WsMethod.UnsubscribeMethod}","params":["${params.subscriptionId.toHexString}"],"id":0,"jsonrpc":"2.0"}"""
      val unSubscribeRequest = WsRequest.unsubscribe(0, params.subscriptionId)
      write(unSubscribeRequest) is validUnSubscriptionReqJson
      unSubscribeRequest is read[WsRequest](validUnSubscriptionReqJson)

      val jsonRpcSubscribeRequest =
        RequestUnsafe("2.0", WsMethod.SubscribeMethod, ujson.Arr(ujson.Str(params.eventType)), 0)
      val expectedSubscribeRequest = WsRequest.fromJsonRpc(jsonRpcSubscribeRequest).rightValue
      expectedSubscribeRequest is read[WsRequest](write(expectedSubscribeRequest))

      val jsonRpcUnSubscribeRequest =
        RequestUnsafe(
          "2.0",
          WsMethod.UnsubscribeMethod,
          ujson.Arr(ujson.Str(params.subscriptionId.toHexString)),
          0
        )
      val expectedUnSubscribeRequest = WsRequest.fromJsonRpc(jsonRpcUnSubscribeRequest).rightValue
      expectedUnSubscribeRequest is read[WsRequest](write(expectedUnSubscribeRequest))
    }
  }

  "WsRequest" should "pass ser/deser round-trip for contract event subscription/unsubscription" in {
    val contractEventParams = ContractEventsSubscribeParams.fromSingle(0, contractAddress_0)
    val validSubscriptionReqJson =
      s"""{"method":"${WsMethod.SubscribeMethod}","params":["${ContractEventsSubscribeParams.Contract}",$EventIndex_0,["${contractAddress_0.toBase58}"]],"id":0,"jsonrpc":"2.0"}"""
    val subscribeRequest = WsRequest.subscribe(0, contractEventParams)
    write(subscribeRequest) is validSubscriptionReqJson
    subscribeRequest is read[WsRequest](validSubscriptionReqJson)

    val validUnSubscriptionReqJson =
      s"""{"method":"${WsMethod.UnsubscribeMethod}","params":["${contractEventParams.subscriptionId.toHexString}"],"id":$EventIndex_0,"jsonrpc":"2.0"}"""
    val unSubscribeRequest = WsRequest.unsubscribe(0, contractEventParams.subscriptionId)
    write(unSubscribeRequest) is validUnSubscriptionReqJson
    unSubscribeRequest is read[WsRequest](validUnSubscriptionReqJson)

    val eventType  = ujson.Str(ContractEventsSubscribeParams.Contract)
    val eventIndex = ujson.Num(0)
    val addresses  = ujson.Arr(ujson.Str(contractAddress_0.toBase58))

    val jsonRpcSubscribeRequest =
      RequestUnsafe("2.0", WsMethod.SubscribeMethod, ujson.Arr(eventType, eventIndex, addresses), 0)
    val expectedSubscribeRequest = WsRequest.fromJsonRpc(jsonRpcSubscribeRequest).rightValue
    expectedSubscribeRequest is read[WsRequest](write(expectedSubscribeRequest))

    val jsonRpcUnSubscribeRequest =
      RequestUnsafe(
        "2.0",
        WsMethod.UnsubscribeMethod,
        ujson.Arr(
          ujson.Str(
            ContractEventsSubscribeParams
              .fromSingle(0, contractAddress_0)
              .subscriptionId
              .toHexString
          )
        ),
        0
      )
    val expectedUnSubscribeRequest = WsRequest.fromJsonRpc(jsonRpcUnSubscribeRequest).rightValue
    expectedUnSubscribeRequest is read[WsRequest](write(expectedUnSubscribeRequest))
  }

  "WsRequest" should "not allow for building contract event subscription without contract address" in {
    assertThrows[AssertionError](ContractEventsSubscribeParams.from(0, AVector.empty))

    val invalidSubscriptionRequest =
      s"""{"method":"${WsMethod.SubscribeMethod}","params":["${ContractEventsSubscribeParams.Contract}",$EventIndex_0,[]],"id":0,"jsonrpc":"2.0"}"""

    assertThrows[JsonRPC.Error](read[WsRequest](invalidSubscriptionRequest))

    val eventType  = ujson.Str(ContractEventsSubscribeParams.Contract)
    val eventIndex = ujson.Num(0)
    val invalidJsonRpcSubscribeRequest =
      RequestUnsafe(
        "2.0",
        WsMethod.SubscribeMethod,
        ujson.Arr(eventType, eventIndex, ujson.Arr()),
        0
      )
    WsRequest.fromJsonRpc(invalidJsonRpcSubscribeRequest).isLeft is true
  }

  "WsNotificationParams" should "pass ser/deser round-trip for notifications" in {
    val blockAndEvents = BlockAndEvents(
      BlockEntry.from(dummyBlock, 0).rightValue,
      contractEventByBlockHash
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

    val contractEventParams = ContractEventsSubscribeParams.fromSingle(0, contractAddress_0)
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

  "WsUtils" should "convert VertxFuture to Scala Future" in {
    import WsUtils._
    VertxFuture
      .succeededFuture("Success")
      .asScala
      .onComplete {
        case Success(value) => value is "Success"
        case Failure(_)     => fail("The future should not fail")
      }(Implicits.global)

    val exception                              = new RuntimeException("Test Failure")
    val failedVertxFuture: VertxFuture[String] = VertxFuture.failedFuture(exception)
    failedVertxFuture.asScala
      .onComplete {
        case Success(_)  => fail("The future should not succeed")
        case Failure(ex) => ex is exception
      }(Implicits.global)
  }
}
