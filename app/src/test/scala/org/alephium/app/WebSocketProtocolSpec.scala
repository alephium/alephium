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

import scala.collection.immutable.TreeMap

import org.alephium.app.WsParams.{FilteredSubscription, Subscription, Unsubscription}
import org.alephium.json.Json._
import org.alephium.util.AlephiumSpec

class WebSocketProtocolSpec extends AlephiumSpec {

  "WebSocketProtocol" should "refuse all requests that are not JsonRPC compliant" in {
    val method    = Subscription.MethodType
    val eventType = Subscription.BlockEvent
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

  "WsRequest" should "pass R/W round-trip for simple subscription" in {
    Subscription.eventTypes.foreach { eventType =>
      val method = Subscription.MethodType
      val validReqJson =
        s"""{"jsonrpc": "2.0", "id": 0, "method": "$method", "params": ["$eventType"]}"""
      write(WsRequest.subscribe(0, Subscription(eventType))) is read[WsRequest](validReqJson)
    }
  }

  "WsRequest" should "pass R/W round-trip for filtered subscription" in {
    FilteredSubscription.eventTypes.foreach { eventType =>
      val method = Subscription.MethodType
      val validReqJson =
        s"""{"jsonrpc": "2.0", "id": 0, "method": "$method", "params": ["$eventType", {"address": "xyz"}]}"""
      val expectedRequest = WsRequest.subscribeWithFilter(
        0,
        FilteredSubscription(eventType, TreeMap("address" -> "xyz"))
      )
      write(expectedRequest) is read[WsRequest](validReqJson)

      WsRequest
        .fromJsonString(
          s"""{"jsonrpc": "2.0", "id": 0, "method": "$method", "params": ["$eventType"]}"""
        )
        .isLeft is true
    }
  }

  "WsRequest" should "pass R/W round-trip for unsubscription from event type" in {
    val method = Unsubscription.MethodType
    Subscription.eventTypes.foreach { eventType =>
      val subscriptionId = Subscription(eventType).subscriptionId
      val validReqJson =
        s"""{"jsonrpc": "2.0", "id": 0, "method": "$method", "params": ["$subscriptionId"]}"""
      write(WsRequest.unsubscribe(0, subscriptionId)) is read[WsRequest](validReqJson)
    }
  }

  "WsRequest" should "pass R/W round-trip for unsubscription of filtered event type" in {
    val method = Unsubscription.MethodType
    FilteredSubscription.eventTypes.foreach { eventType =>
      val subscriptionId =
        FilteredSubscription(eventType, TreeMap("address" -> "xyz")).subscriptionId
      val validReqJson =
        s"""{"jsonrpc": "2.0", "id": 0, "method": "$method", "params": ["$subscriptionId"]}"""
      write(WsRequest.unsubscribe(0, subscriptionId)) is read[WsRequest](validReqJson)
    }
  }
}
