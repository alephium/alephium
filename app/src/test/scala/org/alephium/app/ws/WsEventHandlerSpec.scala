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
  ContractEvent,
  TransactionTemplate,
  ValU256
}
import org.alephium.app.ServerFixture
import org.alephium.app.ws.WsParams.{
  ContractEventsSubscribeParams,
  SimpleSubscribeParams,
  WsBlockNotificationParams,
  WsContractNotificationParams,
  WsNotificationParams,
  WsTxNotificationParams
}
import org.alephium.json.Json._
import org.alephium.protocol.model.{Address, ContractId, TxGenerators}
import org.alephium.rpc.model.JsonRPC
import org.alephium.util._

class WsEventHandlerSpec extends WsSpec with ServerFixture with TxGenerators {

  it should "subscribe event handler into event bus" in new WsServerFixture {
    val subscriptionHandler =
      WsSubscriptionHandler.apply(vertx, system, networkConfig.maxWsConnections)
    val eventHandler =
      WsEventHandler.getSubscribedEventHandler(node.eventBus, subscriptionHandler, system)
    node.eventBus
      .ask(EventBus.ListSubscribers)
      .mapTo[EventBus.Subscribers]
      .futureValue
      .value
      .contains(eventHandler.ref) is true
  }

  it should "make Block Notification from Event round-trip" in {
    val blockAndEvents = BlockAndEvents(BlockEntry.from(dummyBlock, 0).rightValue, AVector.empty)
    val notificationParams: WsNotificationParams =
      WsBlockNotificationParams(SimpleSubscribeParams.Block.subscriptionId, blockAndEvents)
    read[JsonRPC.Notification](notificationParams.asJsonRpcNotification)
  }

  it should "make Tx Notification from Event round-trip" in {
    val notificationParams: WsNotificationParams =
      WsTxNotificationParams(
        SimpleSubscribeParams.Tx.subscriptionId,
        TransactionTemplate.fromProtocol(transactionGen().sample.get.toTemplate, TimeStamp.now())
      )
    read[JsonRPC.Notification](notificationParams.asJsonRpcNotification)
  }

  it should "make Contract Notification from Event round-trip" in {
    val subscribeParams =
      ContractEventsSubscribeParams.from(EventIndex_0, AVector(contractAddress_0))
    val notificationParams: WsNotificationParams =
      WsContractNotificationParams(
        subscribeParams.subscriptionId,
        ContractEvent(
          blockHashGen.sample.get,
          txIdGen.sample.get,
          Address.contract(ContractId.hash("foo")),
          EventIndex_0,
          AVector(ValU256(U256.unsafe(5)))
        )
      )
    read[JsonRPC.Notification](notificationParams.asJsonRpcNotification)
  }
}
