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

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import akka.testkit.TestProbe
import org.scalatest.Assertion

import org.alephium.json.Json._
import org.alephium.rpc.model.JsonRPC
import org.alephium.util._
import org.alephium.ws.WsClient
import org.alephium.ws.WsParams._
import org.alephium.ws.WsUtils._

class WebSocketTest extends AlephiumActorSpec {

  trait WebSocketFixture extends CliqueFixture with WsNotificationParamsCodec {
    val clique = bootClique(nbOfNodes = 1)
    clique.start()

    val restPort = clique.getRestPort(clique.getGroup(address).group)

    implicit val ec: ExecutionContext = system.dispatcher

    val wsClient = startWsClient(restPort).futureValue

    val probe = TestProbe()

    // Overriding message handler to simulate raw websocket connection, not using our client.
    wsClient.underlying.textMessageHandler((message: String) => {
      probe.ref ! message
    })

    def cleanup(): Unit = {
      if (!wsClient.isClosed) {
        wsClient.close().futureValue
      }
      clique.stop()
    }

    def subscribeToEvent(wsClient: WsClient, subscriptionType: String): Unit = {
      // Create subscription JSON
      val subscribeJson = jsonRpc("subscribe", s"""["$subscriptionType"]""")
      wsClient.underlying.writeTextMessage(subscribeJson).asScala.map(_ => ()).futureValue
    }

    def expectSubscriptionSuccess(): Assertion = {
      val message = probe.expectMsgType[String](30.seconds)
      val success = read[JsonRPC.Response.Success](message)
      success.result is a[ujson.Str]
    }

    def expectSubscriptionFailure(): Assertion = {
      val message = probe.expectMsgType[String](30.seconds)
      val success = read[JsonRPC.Response.Failure](message)
      success.error is a[JsonRPC.Error]
    }

    def expectBlockNotification(): Assertion = {
      val message      = probe.expectMsgType[String](30.seconds)
      val notification = read[JsonRPC.Notification](message)
      notification.method is "subscription"
      read[WsBlockNotificationParams](notification.params) is a[WsBlockNotificationParams]
    }

    def expectTxNotification(): Assertion = {
      val message      = probe.expectMsgType[String](30.seconds)
      val notification = read[JsonRPC.Notification](message)
      notification.method is "subscription"
      read[WsTxNotificationParams](notification.params) is a[WsTxNotificationParams]
    }
  }

  it should "establish WebSocket connection" in new WebSocketFixture {
    try {
      wsClient.isClosed is false

      wsClient.close().futureValue is ()

      eventually {
        wsClient.isClosed is true
      }
    } finally {
      cleanup()
    }
  }

  it should "receive block notifications when subscribed" in new WebSocketFixture {
    try {
      clique.startMining()

      subscribeToEvent(wsClient, "block")

      expectSubscriptionSuccess()

      expectBlockNotification()
    } finally {
      cleanup()
    }
  }

  it should "receive transaction notifications when subscribed" in new WebSocketFixture {
    try {
      subscribeToEvent(wsClient, "tx")

      expectSubscriptionSuccess()

      transfer(publicKey, transferAddress, transferAmount, privateKey, restPort)

      expectTxNotification()
    } finally {
      cleanup()
    }
  }

  it should "refuse second subscription attempt" in new WebSocketFixture {
    try {
      subscribeToEvent(wsClient, "block")

      expectSubscriptionSuccess()

      subscribeToEvent(wsClient, "block")

      expectSubscriptionFailure()
    } finally {
      cleanup()
    }
  }
}
