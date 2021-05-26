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
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{ChainIndex, TransactionTemplate}
import org.alephium.util.{ActorRefT, AVector, Duration, TimeStamp}
import org.alephium.util.EventStream.Subscriber

object ViewHandler {
  def props(blockFlow: BlockFlow, txHandler: ActorRefT[TxHandler.Command])(implicit
      brokerConfig: BrokerConfig
  ): Props = Props(
    new ViewHandler(blockFlow, txHandler)
  )

  sealed trait Command

  def needUpdate(chainIndex: ChainIndex)(implicit brokerConfig: BrokerConfig): Boolean = {
    brokerConfig.contains(chainIndex.from) || chainIndex.isIntraGroup
  }
}

class ViewHandler(blockFlow: BlockFlow, txHandler: ActorRefT[TxHandler.Command])(implicit
    brokerConfig: BrokerConfig
) extends IOBaseActor
    with Subscriber {
  var lastUpdated: TimeStamp = TimeStamp.zero

  subscribeEvent(self, classOf[ChainHandler.FlowDataAdded])

  override def receive: Receive = { case ChainHandler.FlowDataAdded(data, _, addedAt) =>
    // We only update best deps for the following 2 cases:
    //  1. the block belongs to the groups of the node
    //  2. the header belongs to intra-group chain
    if (addedAt >= lastUpdated && ViewHandler.needUpdate(data.chainIndex)) {
      lastUpdated = TimeStamp.now()
      escapeIOError(blockFlow.updateBestDeps()) { newReadyTxs =>
        broadcastReadyTxs(newReadyTxs)
      }
    }
  }

  def broadcastReadyTxs(txs: AVector[TransactionTemplate]): Unit = {
    if (txs.nonEmpty) {
      // TODO: maybe broadcast it based on peer sync status
      // delay this broadcast so that peers have download this block
      scheduleOnce(
        txHandler.ref,
        TxHandler.Broadcast(txs),
        Duration.ofSecondsUnsafe(2)
      )
    }
  }
}
