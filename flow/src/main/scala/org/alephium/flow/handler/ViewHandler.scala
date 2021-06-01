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

package org.alephium.flow.handler

import akka.actor.Props

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.model.DataOrigin
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{ChainIndex, TransactionTemplate}
import org.alephium.util.{ActorRefT, AVector, Duration, EventStream, TimeStamp}
import org.alephium.util.EventStream.{Publisher, Subscriber}

object ViewHandler {
  def props(blockFlow: BlockFlow, txHandler: ActorRefT[TxHandler.Command])(implicit
      brokerConfig: BrokerConfig
  ): Props = Props(
    new ViewHandler(blockFlow, txHandler)
  )

  sealed trait Command

  sealed trait Event
  final case class ViewUpdated(chainIndex: ChainIndex, origin: DataOrigin)
      extends Event
      with EventStream.Event

  def needUpdate(chainIndex: ChainIndex)(implicit brokerConfig: BrokerConfig): Boolean = {
    brokerConfig.contains(chainIndex.from) || chainIndex.isIntraGroup
  }
}

class ViewHandler(blockFlow: BlockFlow, txHandler: ActorRefT[TxHandler.Command])(implicit
    brokerConfig: BrokerConfig
) extends IOBaseActor
    with Subscriber
    with Publisher {
  var lastUpdated: TimeStamp = TimeStamp.zero

  subscribeEvent(self, classOf[ChainHandler.FlowDataAdded])

  override def receive: Receive = { case ChainHandler.FlowDataAdded(data, origin, addedAt) =>
    // We only update best deps for the following 2 cases:
    //  1. the block belongs to the groups of the node
    //  2. the header belongs to intra-group chain
    val chainIndex = data.chainIndex
    if (addedAt >= lastUpdated && ViewHandler.needUpdate(chainIndex)) {
      lastUpdated = TimeStamp.now()
      escapeIOError(blockFlow.updateBestDeps()) { newReadyTxs =>
        broadcastReadyTxs(newReadyTxs)
      }
    }
    publishEvent(ViewHandler.ViewUpdated(chainIndex, origin))
  }

  def broadcastReadyTxs(txs: AVector[TransactionTemplate]): Unit = {
    if (txs.nonEmpty) {
      // delay this broadcast so that peers have download this block
      scheduleOnce(
        txHandler.ref,
        TxHandler.Broadcast(txs),
        Duration.ofSecondsUnsafe(2)
      )
    }
  }
}
