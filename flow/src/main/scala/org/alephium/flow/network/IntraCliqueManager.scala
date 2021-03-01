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

import akka.actor.{ActorRef, Props, Terminated}
import akka.io.Tcp

import org.alephium.flow.FlowMonitor
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.AllHandlers
import org.alephium.flow.network.broker.BrokerHandler
import org.alephium.flow.network.intraclique.{InboundBrokerHandler, OutboundBrokerHandler}
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{BrokerInfo, CliqueInfo}
import org.alephium.util.{ActorRefT, BaseActor, EventStream}

object IntraCliqueManager {
  def props(cliqueInfo: CliqueInfo,
            blockflow: BlockFlow,
            allHandlers: AllHandlers,
            cliqueManager: ActorRefT[CliqueManager.Command],
            blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command])(
      implicit brokerConfig: BrokerConfig,
      networkSetting: NetworkSetting): Props =
    Props(
      new IntraCliqueManager(cliqueInfo,
                             blockflow,
                             allHandlers,
                             cliqueManager,
                             blockFlowSynchronizer))

  sealed trait Command    extends CliqueManager.Command
  final case object Ready extends Command
}

class IntraCliqueManager(cliqueInfo: CliqueInfo,
                         blockflow: BlockFlow,
                         allHandlers: AllHandlers,
                         cliqueManager: ActorRefT[CliqueManager.Command],
                         blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command])(
    implicit brokerConfig: BrokerConfig,
    networkSetting: NetworkSetting)
    extends BaseActor
    with EventStream.Publisher {

  override def preStart(): Unit = {
    cliqueInfo.intraBrokers.foreach { remoteBroker =>
      if (remoteBroker.brokerId > brokerConfig.brokerId) {
        val address = remoteBroker.address
        log.debug(s"Connect to broker $remoteBroker")
        val props = OutboundBrokerHandler.props(cliqueInfo,
                                                remoteBroker,
                                                blockflow,
                                                allHandlers,
                                                ActorRefT[CliqueManager.Command](self),
                                                blockFlowSynchronizer)
        context.actorOf(props, BaseActor.envalidActorName(s"OutboundBrokerHandler-$address"))
      }
    }

    if (cliqueInfo.brokerNum == 1) {
      cliqueManager ! IntraCliqueManager.Ready
      context become handle(Map.empty)
    } else {
      context become awaitBrokers(Map.empty)
    }
  }

  override def receive: Receive = awaitBrokers(Map.empty)

  // TODO: replace Map with Array for performance
  def awaitBrokers(brokers: Map[Int, (BrokerInfo, ActorRefT[BrokerHandler.Command])]): Receive = {
    case Tcp.Connected(remote, _) =>
      log.debug(s"Connected to $remote")
      val index = cliqueInfo.internalAddresses.indexWhere(_ == remote)
      if (index < brokerConfig.brokerId) {
        // Note: index == -1 is also the right condition
        log.debug(s"The connection from $remote is incoming connection")
        val name = BaseActor.envalidActorName(s"InboundBrokerHandler-$remote")
        val props =
          InboundBrokerHandler.props(cliqueInfo,
                                     remote,
                                     networkSetting.connectionBuild(sender()),
                                     blockflow,
                                     allHandlers,
                                     ActorRefT[CliqueManager.Command](self),
                                     blockFlowSynchronizer)
        context.actorOf(props, name)
        ()
      }
    case CliqueManager.HandShaked(brokerInfo) =>
      log.debug(s"Start syncing with intra-clique node: ${brokerInfo.address}")
      if (brokerInfo.cliqueId == cliqueInfo.id && !brokers.contains(brokerInfo.brokerId)) {
        log.debug(s"Broker connected: $brokerInfo")
        context watch sender()
        val brokerHandler = ActorRefT[BrokerHandler.Command](sender())
        val newBrokers    = brokers + (brokerInfo.brokerId -> (brokerInfo -> brokerHandler))
        checkAllSynced(newBrokers)
      }
    case Terminated(actor) => handleTerminated(actor, brokers)
  }

  def checkAllSynced(newBrokers: Map[Int, (BrokerInfo, ActorRefT[BrokerHandler.Command])]): Unit = {
    if (newBrokers.size == cliqueInfo.brokerNum - 1) {
      log.debug("All Brokers connected")
      cliqueManager ! IntraCliqueManager.Ready
      context become handle(newBrokers)
    } else {
      context become awaitBrokers(newBrokers)
    }
  }

  def handle(brokers: Map[Int, (BrokerInfo, ActorRefT[BrokerHandler.Command])]): Receive = {
    case CliqueManager.BroadCastBlock(block, blockMsg, headerMsg, origin, _) =>
      assume(block.chainIndex.relateTo(brokerConfig))
      log.debug(s"Broadcasting block ${block.shortHex} for ${block.chainIndex}")
      // TODO: optimize this without using iteration
      brokers.foreach {
        case (_, (info, broker)) =>
          if (!origin.isFrom(info)) {
            if (block.chainIndex.relateTo(info)) {
              log.debug(s"Send block ${block.shortHex} to broker $info")
              broker ! BrokerHandler.Send(blockMsg)
            } else {
              log.debug(s"Send header ${block.shortHex} to broker $info")
              broker ! BrokerHandler.Send(headerMsg)
            }
          }
      }
    case Terminated(actor) => handleTerminated(actor, brokers)
  }

  def handleTerminated(actor: ActorRef,
                       brokers: Map[Int, (BrokerInfo, ActorRefT[BrokerHandler.Command])]): Unit = {
    brokers.foreach {
      case (_, (info, broker)) if broker == ActorRefT[BrokerHandler.Command](actor) =>
        log.error(s"Self clique node $info is not functioning, shutdown the system now")
        publishEvent(FlowMonitor.Shutdown)
      case _ => ()
    }
  }
}
