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

import org.alephium.api.model.BlockEntry
import org.alephium.app.WebSocketServer.{EventHandler, WsSubscription}
import org.alephium.flow.handler.AllHandlers.BlockNotify
import org.alephium.json.Json._
import org.alephium.util._

class WebSocketEventHandlerSpec extends AlephiumSpec with ServerFixture {
  import ServerFixture._

  behavior of "eventHandler"

  it should "encode BlockEntry" in {
    val blockEntry = BlockEntry.from(dummyBlock, 0).rightValue
    val result     = writeJs(blockEntry)
    show(result) is write(blockEntry)
  }

  it should "parse subscription EventType" in {
    WsSubscription.parseSubscription("subscribe:block").get is WsSubscription.Block
    WsSubscription.parseSubscription("subscribe:tx").get is WsSubscription.Tx
    // let's be strict
    WsSubscription.parseSubscription("subscribe:xxx:block").isEmpty is true
    WsSubscription.parseSubscription("subscribe : block").isEmpty is true
    WsSubscription.parseSubscription("subscribe,block").isEmpty is true
    WsSubscription.parseSubscription("subscribe block").isEmpty is true
    WsSubscription.parseSubscription("subscribe:gandalf").isEmpty is true
    WsSubscription.parseSubscription("nonsense:frodo").isEmpty is true
  }

  it should "build subscription message" in {
    WsSubscription.buildSubscribeMsg(WsSubscription.Block) is "subscribe:block"
    WsSubscription.buildSubscribeMsg(WsSubscription.Tx) is "subscribe:tx"
  }

  it should "build Notification from Event" in {
    val notification = EventHandler.buildNotification(BlockNotify(dummyBlock, 0)).rightValue
    val blockEntry   = BlockEntry.from(dummyBlock, 0).rightValue
    notification.method is WsSubscription.Block.method
    show(notification.params) is write(blockEntry)
  }
}
