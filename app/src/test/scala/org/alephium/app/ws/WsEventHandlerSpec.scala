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

  it should "build Block Notification from Event" in {
    val blockAndEvents = BlockAndEvents(BlockEntry.from(dummyBlock, 0).rightValue, AVector.empty)
    val params: WsNotificationParams =
      WsBlockNotificationParams(SimpleSubscribeParams.Block.subscriptionId, blockAndEvents)
    val notification = params.buildJsonRpcNotification
    write(notification.params) is write(params)
  }

  it should "build Tx Notification from Event" in {
    val params: WsNotificationParams =
      WsTxNotificationParams(
        SimpleSubscribeParams.Tx.subscriptionId,
        TransactionTemplate.fromProtocol(transactionGen().sample.get.toTemplate, TimeStamp.now())
      )
    val notification = params.buildJsonRpcNotification
    write(notification.params) is write(params)
  }

  it should "build Contract Notification from Event" in {
    val subscribeParams =
      ContractEventsSubscribeParams.from(ZeroEventIndex, AVector(contractAddress))
    val notificationParams: WsNotificationParams =
      WsContractNotificationParams(
        subscribeParams.subscriptionId,
        ContractEvent(
          blockHashGen.sample.get,
          txIdGen.sample.get,
          Address.contract(ContractId.hash("foo")),
          ZeroEventIndex,
          AVector(ValU256(U256.unsafe(5)))
        )
      )
    val notification = notificationParams.buildJsonRpcNotification
    write(notification.params) is write(notificationParams)
  }
}
