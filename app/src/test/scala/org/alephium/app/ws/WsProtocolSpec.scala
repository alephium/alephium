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

import scala.collection.immutable.TreeMap
import scala.concurrent.ExecutionContext.Implicits
import scala.concurrent.Future
import scala.util.{Failure, Success}

import io.vertx.core.{Future => VertxFuture}

import org.alephium.app.ws.WsParams.{FilteredSubscribeParams, SubscribeParams}
import org.alephium.json.Json._
import org.alephium.util.AlephiumSpec

class WsProtocolSpec extends AlephiumSpec {

  "WebSocketProtocol" should "refuse all WsRequest that are not JsonRPC compliant" in {
    val method    = WsMethod.SubscribeMethod
    val eventType = SubscribeParams.BlockEvent
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
    SubscribeParams.eventTypes.foreach { eventType =>
      val method       = WsMethod.SubscribeMethod
      val validReqJson = s"""{"method":"$method","params":["$eventType"],"id":0,"jsonrpc":"2.0"}"""
      val expectedRequest = WsRequest.subscribe(0, SubscribeParams(eventType))
      write(expectedRequest) is validReqJson
      read[WsRequest](validReqJson) is expectedRequest
    }
  }

  "WsRequest" should "pass R/W round-trip for filtered subscription" in {
    FilteredSubscribeParams.eventTypes.foreach { eventType =>
      val method = WsMethod.SubscribeMethod
      val validReqJson =
        s"""{"method":"$method","params":["$eventType",{"address":"xyz"}],"id":0,"jsonrpc":"2.0"}"""
      val expectedRequest = WsRequest.subscribeWithFilter(
        0,
        FilteredSubscribeParams(eventType, TreeMap("address" -> "xyz"))
      )
      write(expectedRequest) is validReqJson
      read[WsRequest](validReqJson) is expectedRequest

      WsRequest
        .fromJsonString(s"""{"method":"$method","params":["$eventType"],"id":0,"jsonrpc":"2.0"}""")
        .isLeft is true
    }
  }

  "WsRequest" should "pass R/W round-trip for unsubscription from event type" in {
    val method = WsMethod.UnsubscribeMethod
    SubscribeParams.eventTypes.foreach { eventType =>
      val subscriptionId = SubscribeParams(eventType).subscriptionId
      val validReqJson =
        s"""{"method":"$method","params":["$subscriptionId"],"id":0,"jsonrpc":"2.0"}"""
      val expectedRequest = WsRequest.unsubscribe(0, subscriptionId)
      write(expectedRequest) is validReqJson
      read[WsRequest](validReqJson) is expectedRequest
    }
  }

  "WsRequest" should "pass R/W round-trip for unsubscription of filtered event type" in {
    val method = WsMethod.UnsubscribeMethod
    FilteredSubscribeParams.eventTypes.foreach { eventType =>
      val subscriptionId =
        FilteredSubscribeParams(eventType, TreeMap("address" -> "xyz")).subscriptionId
      val validReqJson =
        s"""{"method":"$method","params":["$subscriptionId"],"id":0,"jsonrpc":"2.0"}"""
      val expectedRequest = WsRequest.unsubscribe(0, subscriptionId)
      write(expectedRequest) is validReqJson
      read[WsRequest](validReqJson) is expectedRequest
    }
  }

  "WsUtils" should "convert VertxFuture to Scala Future" in {
    import WsUtils._
    val successfulVertxFuture: VertxFuture[String] = VertxFuture.succeededFuture("Success Result")
    val successfulScalaFuture: Future[String]      = successfulVertxFuture.asScala
    successfulScalaFuture.onComplete {
      case Success(value) =>
        value is "Success Result"
      case Failure(_) =>
        fail("The future should not fail in this test case")
    }(Implicits.global)

    val exception                              = new RuntimeException("Test Failure")
    val failedVertxFuture: VertxFuture[String] = VertxFuture.failedFuture(exception)
    val failedScalaFuture: Future[String]      = failedVertxFuture.asScala

    failedScalaFuture.onComplete {
      case Success(_) =>
        fail("The future should not succeed in this test case")
      case Failure(ex) =>
        ex is exception // Expected exception is thrown
    }(Implicits.global)
  }

}
