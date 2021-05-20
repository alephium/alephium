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

import akka.actor.{Props, Stash}

import org.alephium.flow.core.BlockFlow
import org.alephium.protocol.{BlockHash, Hash}
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.message.{Message, SendHeaders}
import org.alephium.protocol.model._
import org.alephium.util._

object FlowHandler {
  def props(blockFlow: BlockFlow)(implicit brokerConfig: BrokerConfig): Props =
    Props(new FlowHandler(blockFlow))

  sealed trait Command
  final case class GetBlocks(locators: AVector[BlockHash])                   extends Command
  final case class GetHeaders(locators: AVector[BlockHash])                  extends Command
  case object GetSyncLocators                                                extends Command
  final case class GetSyncInventories(locators: AVector[AVector[BlockHash]]) extends Command
  final case class GetIntraSyncInventories(brokerInfo: BrokerInfo)           extends Command
  final case class PrepareBlockFlow(chainIndex: ChainIndex)                  extends Command

  sealed trait Event
  final case class BlockFlowTemplate(
      index: ChainIndex,
      deps: AVector[BlockHash],
      depStateHash: Hash,
      target: Target,
      parentTs: TimeStamp,
      transactions: AVector[Transaction]
  )                                                                     extends Event
  final case class BlocksLocated(blocks: AVector[Block])                extends Event
  final case class SyncInventories(hashes: AVector[AVector[BlockHash]]) extends Event
  final case class SyncLocators(selfBrokerInfo: BrokerConfig, hashes: AVector[AVector[BlockHash]])
      extends Command {
    def filerFor(another: BrokerGroupInfo): AVector[AVector[BlockHash]] = {
      val (groupFrom, groupUntil) = selfBrokerInfo.calIntersection(another)
      if (groupUntil <= groupFrom) {
        AVector.empty
      } else {
        hashes.slice(
          (groupFrom - selfBrokerInfo.groupFrom) * selfBrokerInfo.groups,
          (groupUntil - selfBrokerInfo.groupFrom) * selfBrokerInfo.groups
        )
      }
    }
  }
  final case class BlockNotify(header: BlockHeader, height: Int) extends EventBus.Event
}

// Queue all the work related to miner, rpc server, etc. in this actor
class FlowHandler(blockFlow: BlockFlow)(implicit
    brokerConfig: BrokerConfig
) extends IOBaseActor
    with Stash {
  import FlowHandler._

  override def receive: Receive = handleRelay orElse handleSync

  def handleRelay: Receive = {
    case GetHeaders(locators) =>
      locators.flatMapE(blockFlow.getHeadersAfter) match {
        case Left(error) =>
          log.warning(s"Failure while getting block headers: $error")
        case Right(headers) =>
          sender() ! Message(SendHeaders(headers))
      }
    case GetBlocks(locators: AVector[BlockHash]) =>
      locators.flatMapE(blockFlow.getBlocksAfter) match {
        case Left(error) =>
          log.warning(s"IO Failure while getting blocks: $error")
        case Right(blocks) =>
          sender() ! BlocksLocated(blocks)
      }
    case PrepareBlockFlow(chainIndex) => prepareBlockFlow(chainIndex)
  }

  def handleSync: Receive = {
    case GetSyncLocators =>
      escapeIOError(blockFlow.getSyncLocators()) { locators =>
        sender() ! SyncLocators(brokerConfig, locators)
      }
    case GetSyncInventories(locators) =>
      escapeIOError(blockFlow.getSyncInventories(locators)) { inventories =>
        sender() ! SyncInventories(inventories)
      }
    case GetIntraSyncInventories(brokerInfo) =>
      escapeIOError(blockFlow.getIntraSyncInventories(brokerInfo)) { inventories =>
        sender() ! SyncInventories(inventories)
      }
  }

  def prepareBlockFlow(chainIndex: ChainIndex): Unit = {
    assume(brokerConfig.contains(chainIndex.from))
    val template = blockFlow.prepareBlockFlow(chainIndex)
    template match {
      case Left(error) =>
        log.warning(s"Failure while computing best dependencies: ${error.toString}")
      case Right(message) =>
        sender() ! message
    }
  }
}
