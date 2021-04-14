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

package org.alephium.flow.network

import akka.actor.{ActorRef, Props, Stash}
import akka.io.Tcp
import akka.util.ByteString

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.AllHandlers
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.broker.ConnectionType
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model._
import org.alephium.util.{ActorRefT, AVector, BaseActor, EventStream}

object CliqueManager {
  def props(
      blockflow: BlockFlow,
      allHandlers: AllHandlers,
      discoveryServer: ActorRefT[DiscoveryServer.Command],
      blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command]
  )(implicit brokerConfig: BrokerConfig, networkSetting: NetworkSetting): Props =
    Props(new CliqueManager(blockflow, allHandlers, discoveryServer, blockFlowSynchronizer))

  trait Command
  final case class Start(cliqueInfo: CliqueInfo) extends Command
  final case class BroadCastBlock(
      block: Block,
      blockMsg: ByteString,
      headerMsg: ByteString,
      origin: DataOrigin,
      isRecent: Boolean
  ) extends Command
      with EventStream.Event
  final case class BroadCastTx(
      txs: AVector[TransactionTemplate],
      txMsg: ByteString,
      chainIndex: ChainIndex,
      origin: DataOrigin
  ) extends Command
      with EventStream.Event
  final case class HandShaked(brokerInfo: BrokerInfo, connectionType: ConnectionType)
      extends Command
  final case class Synced(brokerInfo: BrokerInfo) extends Command
  final case object IsSelfCliqueReady             extends Command
}

class CliqueManager(
    blockflow: BlockFlow,
    allHandlers: AllHandlers,
    discoveryServer: ActorRefT[DiscoveryServer.Command],
    blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command]
)(implicit brokerConfig: BrokerConfig, networkSetting: NetworkSetting)
    extends BaseActor
    with Stash
    with EventStream.Subscriber {
  import CliqueManager._

  var selfCliqueReady: Boolean = false

  override def receive: Receive = isSelfCliqueSynced orElse awaitStart()

  def awaitStart(): Receive = {
    case Start(cliqueInfo) =>
      log.debug("Start intra and inter clique managers")
      val intraCliqueManager =
        context.actorOf(
          IntraCliqueManager
            .props(cliqueInfo, blockflow, allHandlers, ActorRefT(self), blockFlowSynchronizer),
          "IntraCliqueManager"
        )
      unstashAll()
      context.become(
        isSelfCliqueSynced orElse awaitIntraCliqueReady(intraCliqueManager, cliqueInfo)
      )

    case _ => stash()
  }

  def awaitIntraCliqueReady(intraCliqueManager: ActorRef, cliqueInfo: CliqueInfo): Receive = {
    case IntraCliqueManager.Ready =>
      log.debug(s"Intra clique manager is ready")
      val props = InterCliqueManager.props(
        cliqueInfo,
        blockflow,
        allHandlers,
        discoveryServer,
        blockFlowSynchronizer
      )
      val interCliqueManager = context.actorOf(props, "InterCliqueManager")
      selfCliqueReady = true
      subscribeEvent(self, classOf[BroadCastTx])
      subscribeEvent(self, classOf[BroadCastBlock])

      unstashAll()
      context become (handleWith(intraCliqueManager, interCliqueManager) orElse isSelfCliqueSynced)
    case c: Tcp.Connected =>
      intraCliqueManager.forward(c)

    case _ => stash()
  }

  def handleWith(intraCliqueManager: ActorRef, interCliqueManager: ActorRef): Receive = {
    case message: CliqueManager.BroadCastBlock =>
      intraCliqueManager ! message
      if (message.isRecent) {
        interCliqueManager ! message
      }
    case message: CliqueManager.BroadCastTx =>
      interCliqueManager ! message

    case message @ InterCliqueManager.GetSyncStatuses =>
      interCliqueManager.forward(message)

    case c: Tcp.Connected =>
      interCliqueManager.forward(c)
  }

  def isSelfCliqueSynced: Receive = { case IsSelfCliqueReady =>
    sender() ! selfCliqueReady
  }
}
