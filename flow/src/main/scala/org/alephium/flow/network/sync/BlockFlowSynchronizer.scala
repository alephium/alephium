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

package org.alephium.flow.network.sync

import java.net.InetSocketAddress

import akka.actor.{Cancellable, Props, Terminated}

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.{AllHandlers, FlowHandler}
import org.alephium.flow.network.broker.{BrokerHandler, BrokerStatusTracker}
import org.alephium.protocol.Hash
import org.alephium.protocol.model.BrokerInfo
import org.alephium.util.{ActorRefT, AVector, BaseActor, Duration}

object BlockFlowSynchronizer {
  def props(blockflow: BlockFlow, allHandlers: AllHandlers): Props =
    Props(new BlockFlowSynchronizer(blockflow, allHandlers))

  sealed trait Command
  final case class HandShaked(brokerInfo: BrokerInfo)              extends Command
  case object Sync                                                 extends Command
  final case class SyncInventories(hashes: AVector[AVector[Hash]]) extends Command
  final case class BlockFinalized(hash: Hash)                      extends Command
}

class BlockFlowSynchronizer(val blockflow: BlockFlow, val allHandlers: AllHandlers)
    extends BaseActor
    with DownloadTracker
    with BrokerStatusTracker {
  import BlockFlowSynchronizer._

  var syncTick: Option[Cancellable] = None

  override def preStart(): Unit = {
    super.preStart()
    syncTick = Some(scheduleCancellable(self, Sync, Duration.ofSecondsUnsafe(2)))
  }

  override def receive: Receive = {
    case HandShaked(remoteBrokerInfo) =>
      log.debug(s"HandShaked with ${remoteBrokerInfo.address}")
      context.watch(sender())
      brokerInfos += ActorRefT[BrokerHandler.Command](sender()) -> remoteBrokerInfo
    case Sync =>
      log.debug(s"Send sync requests to the network")
      allHandlers.flowHandler ! FlowHandler.GetSyncLocators
    case FlowHandler.SyncLocators(locators) =>
      samplePeers.foreach(_ ! BrokerHandler.SyncLocators(locators))
    case SyncInventories(hashes) => download(hashes)
    case BlockFinalized(hash)    => finalized(hash)
    case Terminated(broker) =>
      log.debug(s"Connection to ${remoteAddress(ActorRefT(broker))} is closing")
      brokerInfos -= ActorRefT(broker)
  }

  private def remoteAddress(broker: ActorRefT[BrokerHandler.Command]): InetSocketAddress = {
    brokerInfos(broker).address
  }
}
