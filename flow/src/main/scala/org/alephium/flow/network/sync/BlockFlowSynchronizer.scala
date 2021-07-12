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

import akka.actor.{Props, Terminated}

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.{AllHandlers, FlowHandler, IOBaseActor}
import org.alephium.flow.network._
import org.alephium.flow.network.broker.{BrokerHandler, BrokerStatusTracker}
import org.alephium.protocol.BlockHash
import org.alephium.protocol.model.BrokerInfo
import org.alephium.util.{ActorRefT, AVector}
import org.alephium.util.EventStream.Subscriber

object BlockFlowSynchronizer {
  def props(blockflow: BlockFlow, allHandlers: AllHandlers): Props =
    Props(new BlockFlowSynchronizer(blockflow, allHandlers))

  sealed trait Command
  final case class HandShaked(brokerInfo: BrokerInfo)                   extends Command
  case object Sync                                                      extends Command
  final case class SyncInventories(hashes: AVector[AVector[BlockHash]]) extends Command
  final case class BlockFinalized(hash: BlockHash)                      extends Command
  case object CleanDownloading                                          extends Command
}

class BlockFlowSynchronizer(val blockflow: BlockFlow, val allHandlers: AllHandlers)
    extends IOBaseActor
    with Subscriber
    with DownloadTracker
    with BrokerStatusTracker {
  import BlockFlowSynchronizer._

  var nodeSynced: Boolean = false

  override def preStart(): Unit = {
    super.preStart()
    scheduleSync()
    scheduleCancellable(self, CleanDownloading, syncCleanupFrequency)
    subscribeEvent(self, classOf[InterCliqueManager.SyncedResult])
  }

  override def receive: Receive = {
    case HandShaked(remoteBrokerInfo) =>
      log.debug(s"HandShaked with ${remoteBrokerInfo.address}")
      context.watch(sender())
      brokerInfos += ActorRefT[BrokerHandler.Command](sender()) -> remoteBrokerInfo
    case Sync =>
      if (brokerInfos.nonEmpty) {
        log.debug(s"Send sync requests to the network")
        allHandlers.flowHandler ! FlowHandler.GetSyncLocators
      }
      scheduleSync()
    case flowLocators: FlowHandler.SyncLocators =>
      samplePeers.foreach { case (actor, brokerInfo) =>
        actor ! BrokerHandler.SyncLocators(flowLocators.filerFor(brokerInfo))
      }
    case SyncInventories(hashes) => download(hashes)
    case BlockFinalized(hash)    => finalized(hash)
    case CleanDownloading        => cleanupDownloading(syncExpiryPeriod)
    case Terminated(broker) =>
      log.debug(s"Connection to ${remoteAddress(ActorRefT(broker))} is closing")
      brokerInfos -= ActorRefT(broker)
    case InterCliqueManager.SyncedResult(isSynced) => nodeSynced = isSynced
  }

  private def remoteAddress(broker: ActorRefT[BrokerHandler.Command]): InetSocketAddress = {
    brokerInfos(broker).address
  }

  def scheduleSync(): Unit = {
    val frequency = if (nodeSynced) stableSyncFrequency else syncFrequency
    scheduleCancellableOnce(self, Sync, frequency)
    ()
  }
}
