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

import akka.testkit.TestProbe
import io.vertx.core.http.WebSocket
import org.scalatest.EitherValues

import org.alephium.app.WebSocketServer.WsEventType
import org.alephium.flow.handler.FlowHandler.BlockNotify
import org.alephium.json.Json._
import org.alephium.protocol.model._
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util._

class WebSocketServerSpec
    extends AlephiumFutureSpec
    with NoIndexModelGenerators
    with EitherValues
    with NumericHelpers {

  behavior of "ws"

  it should "receive multiple events by multiple websockets" in new RouteWS {
    override def behavior(probe: TestProbe)(ws: WebSocket): WebSocket = {
      ws.textMessageHandler { blockNotify =>
        probe.ref ! blockNotify
      }
      ws.writeTextMessage(s"subscribe:${WsEventType.Block.name}")
      ws.writeTextMessage(s"subscribe:${WsEventType.Tx.name}")
      ws

    }
    checkWS(
      10,
      (0 to 10).map { _ =>
        (
          BlockNotify(blockGen.sample.get, height = 0),
          probeMsg => read[NotificationUnsafe](probeMsg).asNotification.rightValue.method is "block"
        )
      }
    )
  }
}
