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

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.AllHandlers
import org.alephium.flow.network.broker.ConnectionType
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model._
import org.alephium.util.{ActorRefT, BaseActor}

object CliqueManager {
  def props(
      blockflow: BlockFlow,
      allHandlers: AllHandlers,
      discoveryServer: ActorRefT[DiscoveryServer.Command],
      blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command],
      numBootstrapNodes: Int
  )(implicit brokerConfig: BrokerConfig, networkSetting: NetworkSetting): Props =
    Props(
      new CliqueManager(
        blockflow,
        allHandlers,
        discoveryServer,
        blockFlowSynchronizer,
        numBootstrapNodes
      )
    )

  trait Command
  final case class Start(cliqueInfo: CliqueInfo) extends Command
  final case class HandShaked(
      brokerInfo: BrokerInfo,
      connectionType: ConnectionType,
      clientInfo: String
  ) extends Command
  final case class Synced(brokerInfo: BrokerInfo) extends Command
  final case object IsSelfCliqueReady             extends Command
}

class CliqueManager(
    blockflow: BlockFlow,
    allHandlers: AllHandlers,
    discoveryServer: ActorRefT[DiscoveryServer.Command],
    blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command],
    numBootstrapNodes: Int
)(implicit brokerConfig: BrokerConfig, networkSetting: NetworkSetting)
    extends BaseActor
    with Stash {
  import CliqueManager._

  var selfCliqueReady: Boolean = false

  override def receive: Receive = isSelfCliqueSynced orElse awaitStart()

  def awaitStart(): Receive = {
    case Start(cliqueInfo) =>
      log.info(s"Start intra and inter clique managers, cliqueId: ${cliqueInfo.id.toHexString}")
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
      log.info(s"Intra clique manager is ready")
      val props = InterCliqueManager.props(
        cliqueInfo,
        blockflow,
        allHandlers,
        discoveryServer,
        blockFlowSynchronizer,
        numBootstrapNodes
      )
      val interCliqueManager = context.actorOf(props, "InterCliqueManager")
      selfCliqueReady = true

      unstashAll()
      context become (handleWith(interCliqueManager) orElse isSelfCliqueSynced)
    case c: Tcp.Connected =>
      intraCliqueManager.forward(c)

    case _ => stash()
  }

  def handleWith(interCliqueManager: ActorRef): Receive = {
    case message: InterCliqueManager.Command =>
      interCliqueManager.forward(message)

    case c: Tcp.Connected =>
      interCliqueManager.forward(c)
  }

  def isSelfCliqueSynced: Receive = { case IsSelfCliqueReady =>
    sender() ! selfCliqueReady
  }
}
