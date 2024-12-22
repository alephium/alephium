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

import org.alephium.api.model.{BlockAndEvents, BlockEntry}
import org.alephium.app.ServerFixture
import org.alephium.app.ws.WsParams.{
  SubscribeParams,
  WsBlockNotificationParams,
  WsNotificationParams
}
import org.alephium.json.Json._
import org.alephium.util._

class WsEventHandlerSpec extends AlephiumSpec with ServerFixture {

  it should "subscribe event handler into event bus" in new WsServerFixture {
    val newHandler =
      WsEventHandler.getSubscribedEventHandler(vertx.eventBus(), node.eventBus, system)
    node.eventBus
      .ask(EventBus.ListSubscribers)
      .mapTo[EventBus.Subscribers]
      .futureValue
      .value
      .contains(newHandler.ref) is true
  }

  it should "build Notification from Event" in {
    val blockAndEvents = BlockAndEvents(BlockEntry.from(dummyBlock, 0).rightValue, AVector.empty)
    val params: WsNotificationParams =
      WsBlockNotificationParams(SubscribeParams.Block.subscriptionId, blockAndEvents)
    val notification = WsEventHandler.buildJsonRpcNotification(params)
    write(notification.params) is write(params)
  }
}
