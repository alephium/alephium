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

import org.apache.pekko.actor.{Props, Stash}

import org.alephium.flow.core.BlockFlow
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.message.RequestId
import org.alephium.protocol.model._
import org.alephium.util._

object FlowHandler {
  def props(blockFlow: BlockFlow): Props = Props(new FlowHandler(blockFlow))

  sealed trait Command
  case object GetIntraSyncInventories extends Command
  case object GetChainState           extends Command

  final case class SyncInventories(id: Option[RequestId], hashes: AVector[AVector[BlockHash]])
      extends Command
  final case class UpdateChainState(tips: AVector[ChainTip]) extends Command {
    def filterFor(
        another: BrokerGroupInfo
    )(implicit groupConfig: GroupConfig): AVector[ChainTip] = {
      tips.filter(tip => another.contains(tip.chainIndex.from))
    }
  }
}

// Queue all the work related to miner, rpc server, etc. in this actor
class FlowHandler(blockFlow: BlockFlow) extends IOBaseActor with Stash {
  import FlowHandler._

  override def receive: Receive = handleSync

  def handleSync: Receive = {
    case GetIntraSyncInventories =>
      escapeIOError(blockFlow.getIntraSyncInventories()) { inventories =>
        sender() ! SyncInventories(None, inventories)
      }
    case GetChainState =>
      escapeIOError(blockFlow.getChainTips()) { tips => sender() ! UpdateChainState(tips) }
  }
}
