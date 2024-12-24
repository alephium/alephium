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

import org.alephium.app.ws.WsParams.{ContractEventsSubscribeParams, SimpleSubscribeParams}
import org.alephium.json.Json._
import org.alephium.util.AVector

class WsProtocolSpec extends WsSpec {

  "WebSocketProtocol" should "refuse all WsRequest that are not JsonRPC compliant" in {
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

  "WsRequest" should "pass R/W round-trip for simple subscription" in {
    AVector(SimpleSubscribeParams.Block, SimpleSubscribeParams.Tx).foreach { params =>
      val method = WsMethod.SubscribeMethod
      val validReqJson =
        s"""{"method":"$method","params":["${params.eventType}"],"id":0,"jsonrpc":"2.0"}"""
      val expectedRequest = WsRequest.subscribe(0, params)
      write(expectedRequest) is validReqJson
      read[WsRequest](validReqJson) is expectedRequest
    }
  }

  "WsRequest" should "pass R/W round-trip for contract subscription" in {
    ContractEventsSubscribeParams.eventTypes.foreach { eventType =>
      val method = WsMethod.SubscribeMethod
      val validReqJson =
        s"""{"method":"$method","params":["$eventType",$EventIndex_0,["${contractAddress_0.toBase58}"]],"id":0,"jsonrpc":"2.0"}"""
      val expectedRequest = WsRequest.subscribe(
        0,
        ContractEventsSubscribeParams(eventType, 0, AVector(contractAddress_0))
      )
      write(expectedRequest) is validReqJson
      read[WsRequest](validReqJson) is expectedRequest
    }
  }

  "WsRequest" should "pass R/W round-trip for unsubscription from event type" in {
    val method = WsMethod.UnsubscribeMethod
    SimpleSubscribeParams.eventTypes.foreach { eventType =>
      val subscriptionId = SimpleSubscribeParams(eventType).subscriptionId
      val validReqJson =
        s"""{"method":"$method","params":["$subscriptionId"],"id":0,"jsonrpc":"2.0"}"""
      val expectedRequest = WsRequest.unsubscribe(0, subscriptionId)
      write(expectedRequest) is validReqJson
      read[WsRequest](validReqJson) is expectedRequest
    }
  }

  "WsRequest" should "pass R/W round-trip for unsubscription of contract event type" in {
    val method = WsMethod.UnsubscribeMethod
    ContractEventsSubscribeParams.eventTypes.foreach { eventType =>
      val subscriptionId =
        ContractEventsSubscribeParams(
          eventType,
          EventIndex_0,
          AVector(contractAddress_0)
        ).subscriptionId
      val validReqJson =
        s"""{"method":"$method","params":["$subscriptionId"],"id":0,"jsonrpc":"2.0"}"""
      val expectedRequest = WsRequest.unsubscribe(0, subscriptionId)
      write(expectedRequest) is validReqJson
      read[WsRequest](validReqJson) is expectedRequest
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
