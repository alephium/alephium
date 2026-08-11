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

import org.apache.pekko.testkit.TestProbe
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

    implicit val ec: ExecutionContext = system.dispatcher

    val probe = TestProbe()

    def withRawWsClient[T](body: (WsClient, Int) => T): T = {
      withStartedClique(clique) { _ =>
        val restPort = clique.getRestPort(clique.getGroup(address).group)
        withWsClient(restPort) { wsClient =>
          // Overriding message handler to simulate raw websocket connection, not using our client.
          wsClient.underlying.textMessageHandler((message: String) => {
            probe.ref ! message
          })
          body(wsClient, restPort)
        }
      }
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
    withRawWsClient { (wsClient, _) =>
      wsClient.isClosed is false

      wsClient.close().futureValue is ()

      eventually {
        wsClient.isClosed is true
      }
    }
  }

  it should "receive block notifications when subscribed" in new WebSocketFixture {
    withRawWsClient { (wsClient, _) =>
      withCliqueMining(clique) {
        subscribeToEvent(wsClient, "block")

        expectSubscriptionSuccess()

        expectBlockNotification()
      }
    }
  }

  it should "receive transaction notifications when subscribed" in new WebSocketFixture {
    withRawWsClient { (wsClient, restPort) =>
      subscribeToEvent(wsClient, "tx")

      expectSubscriptionSuccess()

      transfer(publicKey, transferAddress, transferAmount, privateKey, restPort)

      expectTxNotification()
    }
  }

  it should "refuse second subscription attempt" in new WebSocketFixture {
    withRawWsClient { (wsClient, _) =>
      subscribeToEvent(wsClient, "block")

      expectSubscriptionSuccess()

      subscribeToEvent(wsClient, "block")

      expectSubscriptionFailure()
    }
  }
}
