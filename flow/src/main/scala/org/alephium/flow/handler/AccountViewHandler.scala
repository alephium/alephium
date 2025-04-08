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

import akka.actor.{ActorSystem, Props}

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.network.InterCliqueManager
import org.alephium.protocol.config.{BrokerConfig, NetworkConfig}
import org.alephium.protocol.model.{Block, BlockHeader}
import org.alephium.util.{ActorRefT, TimeStamp}

object AccountViewHandler {
  type Command = Nothing

  def build(
      system: ActorSystem,
      blockFlow: BlockFlow,
      namePostfix: String
  )(implicit
      brokerConfig: BrokerConfig,
      networkConfig: NetworkConfig
  ): ActorRefT[Command] = {
    val actor = ActorRefT.build[Command](
      system,
      Props(new AccountViewHandler(blockFlow)),
      s"AccountViewHandler$namePostfix"
    )
    system.eventStream.subscribe(actor.ref, classOf[InterCliqueManager.SyncedResult])
    system.eventStream.subscribe(actor.ref, classOf[ChainHandler.FlowDataAdded])
    actor
  }
}

final class AccountViewHandler(val blockFlow: BlockFlow)(implicit
    val brokerConfig: BrokerConfig,
    val networkConfig: NetworkConfig
) extends IOBaseActor
    with InterCliqueManager.NodeSyncStatus {

  private def tryUpdateAccountView(block: Block): Unit = {
    val now         = TimeStamp.now()
    val hardForkNow = networkConfig.getHardFork(now)
    if (hardForkNow.isDanubeEnabled()) {
      updateAccountView(block)
    } else {
      val hardForkSoon = networkConfig.getHardFork(now.plusMinutesUnsafe(2))
      if (hardForkSoon.isDanubeEnabled()) {
        updateAccountView(block)
      }
    }
  }

  @inline private def updateAccountView(block: Block): Unit = {
    escapeIOError(blockFlow.updateAccountView(block))
  }

  def receive: Receive = handleFlowData orElse updateNodeSyncStatus

  def handleFlowData: Receive = {
    case ChainHandler.FlowDataAdded(block: Block, _, _) =>
      if (isNodeSynced && block.chainIndex.relateTo(brokerConfig)) {
        tryUpdateAccountView(block)
      }
    case ChainHandler.FlowDataAdded(_: BlockHeader, _, _) => ()
  }
}
