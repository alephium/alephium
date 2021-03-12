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

import akka.actor.ActorSystem

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.config.{BrokerConfig, ConsensusConfig}
import org.alephium.protocol.model.ChainIndex
import org.alephium.util.{ActorRefT, EventBus}

final case class AllHandlers(
    flowHandler: ActorRefT[FlowHandler.Command],
    txHandler: ActorRefT[TxHandler.Command],
    dependencyHandler: ActorRefT[DependencyHandler.Command],
    blockHandlers: Map[ChainIndex, ActorRefT[BlockChainHandler.Command]],
    headerHandlers: Map[ChainIndex, ActorRefT[HeaderChainHandler.Command]])(
    implicit brokerConfig: BrokerConfig) {
  def orderedHandlers: Seq[ActorRefT[_]] = {
    (blockHandlers.values ++ headerHandlers.values ++ Seq(txHandler, flowHandler)).toSeq
  }

  def getBlockHandler(chainIndex: ChainIndex): ActorRefT[BlockChainHandler.Command] = {
    assume(chainIndex.relateTo(brokerConfig))
    blockHandlers(chainIndex)
  }

  def getHeaderHandler(chainIndex: ChainIndex): ActorRefT[HeaderChainHandler.Command] = {
    assume(!chainIndex.relateTo(brokerConfig))
    headerHandlers(chainIndex)
  }
}

object AllHandlers {
  def build(system: ActorSystem, blockFlow: BlockFlow, eventBus: ActorRefT[EventBus.Message])(
      implicit brokerConfig: BrokerConfig,
      consensusConfig: ConsensusConfig,
      networkSetting: NetworkSetting): AllHandlers = {
    build(system, blockFlow, eventBus, "")
  }

  def build(system: ActorSystem,
            blockFlow: BlockFlow,
            eventBus: ActorRefT[EventBus.Message],
            namePostfix: String)(implicit brokerConfig: BrokerConfig,
                                 consensusConfig: ConsensusConfig,
                                 networkSetting: NetworkSetting): AllHandlers = {
    val flowProps = FlowHandler.props(blockFlow, eventBus)
    val flowHandler =
      ActorRefT.build[FlowHandler.Command](system, flowProps, s"FlowHandler$namePostfix")
    buildWithFlowHandler(system, blockFlow, flowHandler, namePostfix)
  }

  def buildWithFlowHandler(system: ActorSystem,
                           blockFlow: BlockFlow,
                           flowHandler: ActorRefT[FlowHandler.Command],
                           namePostfix: String)(implicit brokerConfig: BrokerConfig,
                                                consensusConfig: ConsensusConfig,
                                                networkSetting: NetworkSetting): AllHandlers = {
    val txProps        = TxHandler.props(blockFlow)
    val txHandler      = ActorRefT.build[TxHandler.Command](system, txProps, s"TxHandler$namePostfix")
    val blockHandlers  = buildBlockHandlers(system, blockFlow, flowHandler, namePostfix)
    val headerHandlers = buildHeaderHandlers(system, blockFlow, flowHandler, namePostfix)

    val dependencyHandlerProps = DependencyHandler.props(blockFlow, blockHandlers, headerHandlers)
    val dependencyHandler = ActorRefT
      .build[DependencyHandler.Command](system,
                                        dependencyHandlerProps,
                                        s"DependencyHandler$namePostfix")
    flowHandler ! FlowHandler.SetHandler(dependencyHandler)

    AllHandlers(flowHandler, txHandler, dependencyHandler, blockHandlers, headerHandlers)
  }

  private def buildBlockHandlers(system: ActorSystem,
                                 blockFlow: BlockFlow,
                                 flowHandler: ActorRefT[FlowHandler.Command],
                                 namePostfix: String)(
      implicit brokerConfig: BrokerConfig,
      consensusConfig: ConsensusConfig,
      networkSetting: NetworkSetting): Map[ChainIndex, ActorRefT[BlockChainHandler.Command]] = {
    val handlers = for {
      from <- 0 until brokerConfig.groups
      to   <- 0 until brokerConfig.groups
      chainIndex = ChainIndex.unsafe(from, to)
      if chainIndex.relateTo(brokerConfig)
    } yield {
      val handler = ActorRefT.build[BlockChainHandler.Command](
        system,
        BlockChainHandler.props(blockFlow, chainIndex, flowHandler),
        s"BlockChainHandler-$from-$to$namePostfix")
      chainIndex -> handler
    }
    handlers.toMap
  }

  private def buildHeaderHandlers(system: ActorSystem,
                                  blockFlow: BlockFlow,
                                  flowHandler: ActorRefT[FlowHandler.Command],
                                  namePostfix: String)(
      implicit brokerConfig: BrokerConfig,
      consensusConfig: ConsensusConfig): Map[ChainIndex, ActorRefT[HeaderChainHandler.Command]] = {
    val headerHandlers = for {
      from <- 0 until brokerConfig.groups
      to   <- 0 until brokerConfig.groups
      chainIndex = ChainIndex.unsafe(from, to)
      if !chainIndex.relateTo(brokerConfig)
    } yield {
      val headerHander = ActorRefT.build[HeaderChainHandler.Command](
        system,
        HeaderChainHandler.props(blockFlow, chainIndex, flowHandler),
        s"HeaderChainHandler-$from-$to$namePostfix")
      chainIndex -> headerHander
    }
    headerHandlers.toMap
  }
}
