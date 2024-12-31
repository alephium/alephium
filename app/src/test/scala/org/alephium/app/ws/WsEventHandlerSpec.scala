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

import scala.concurrent.duration.DurationInt

import org.alephium.api.model.{BlockAndEvents, BlockEntry, TransactionTemplate}
import org.alephium.app.ws.WsParams._
import org.alephium.json.Json._
import org.alephium.rpc.model.JsonRPC
import org.alephium.util._

class WsEventHandlerSpec extends WsSubscriptionFixture {

  it should "subscribe event handler into event bus" in new WsServerFixture {
    val subscriptionHandler =
      WsSubscriptionHandler.apply(vertx, system, networkConfig.maxWsConnections, 10.seconds)
    val eventHandler =
      WsEventHandler.getSubscribedEventHandler(node.eventBus, subscriptionHandler, system)
    node.eventBus
      .ask(EventBus.ListSubscribers)
      .mapTo[EventBus.Subscribers]
      .futureValue
      .value
      .contains(eventHandler.ref) is true
  }

  it should "make JsonRPC Block Notification ser/deser round-trip" in {
    val blockAndEvents =
      BlockAndEvents(BlockEntry.from(dummyBlock, 0).rightValue, contractEventByBlockHash)
    val notificationParams: WsNotificationParams =
      WsBlockNotificationParams(SimpleSubscribeParams.Block.subscriptionId, blockAndEvents)
    read[JsonRPC.Notification](notificationParams.asJsonRpcNotification)
  }

  it should "make JsonRPC Tx Notification ser/deser round-trip" in {
    val notificationParams: WsNotificationParams =
      WsTxNotificationParams(
        SimpleSubscribeParams.Tx.subscriptionId,
        TransactionTemplate.fromProtocol(transactionGen().sample.get.toTemplate, TimeStamp.now())
      )
    read[JsonRPC.Notification](notificationParams.asJsonRpcNotification)
  }

  it should "make JsonRPC Contract Notification ser/deser round-trip" in {
    val subscribeParams = ContractEventsSubscribeParams.fromSingle(EventIndex_0, contractAddress_0)
    val notificationParams: WsNotificationParams =
      WsContractEventNotificationParams(subscribeParams.subscriptionId, contractEvent)
    read[JsonRPC.Notification](notificationParams.asJsonRpcNotification)
  }
}
