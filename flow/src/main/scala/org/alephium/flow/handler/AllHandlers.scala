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
import org.alephium.flow.io.Storages
import org.alephium.flow.mining.MiningDispatcher
import org.alephium.flow.setting.{MemPoolSetting, MiningSetting, NetworkSetting}
import org.alephium.protocol.config.{BrokerConfig, ConsensusConfig}
import org.alephium.protocol.model.ChainIndex
import org.alephium.protocol.vm.LogConfig
import org.alephium.util.{ActorRefT, EventBus}

final case class AllHandlers(
    flowHandler: ActorRefT[FlowHandler.Command],
    txHandler: ActorRefT[TxHandler.Command],
    dependencyHandler: ActorRefT[DependencyHandler.Command],
    viewHandler: ActorRefT[ViewHandler.Command],
    blockHandlers: Map[ChainIndex, ActorRefT[BlockChainHandler.Command]],
    headerHandlers: Map[ChainIndex, ActorRefT[HeaderChainHandler.Command]]
)(implicit brokerConfig: BrokerConfig) {
  def orderedHandlers: Seq[ActorRefT[_]] = {
    (blockHandlers.values ++ headerHandlers.values ++ Seq(txHandler, flowHandler)).toSeq
  }

  def getBlockHandler(chainIndex: ChainIndex): Option[ActorRefT[BlockChainHandler.Command]] = {
    blockHandlers.get(chainIndex)
  }

  def getBlockHandlerUnsafe(chainIndex: ChainIndex): ActorRefT[BlockChainHandler.Command] = {
    assume(chainIndex.relateTo(brokerConfig))
    blockHandlers(chainIndex)
  }

  def getHeaderHandler(chainIndex: ChainIndex): Option[ActorRefT[HeaderChainHandler.Command]] = {
    headerHandlers.get(chainIndex)
  }

  def getHeaderHandlerUnsafe(chainIndex: ChainIndex): ActorRefT[HeaderChainHandler.Command] = {
    assume(!chainIndex.relateTo(brokerConfig))
    headerHandlers(chainIndex)
  }
}

object AllHandlers {
  // scalastyle:off parameter.number
  def build(
      system: ActorSystem,
      blockFlow: BlockFlow,
      eventBus: ActorRefT[EventBus.Message],
      storages: Storages
  )(implicit
      brokerConfig: BrokerConfig,
      consensusConfig: ConsensusConfig,
      networkSetting: NetworkSetting,
      miningSetting: MiningSetting,
      memPoolSetting: MemPoolSetting,
      logConfig: LogConfig
  ): AllHandlers = {
    build(system, blockFlow, eventBus, "", storages)
  }
  // scalastyle:on parameter.number

  // scalastyle:off parameter.number
  def build(
      system: ActorSystem,
      blockFlow: BlockFlow,
      eventBus: ActorRefT[EventBus.Message],
      namePostfix: String,
      storages: Storages
  )(implicit
      brokerConfig: BrokerConfig,
      consensusConfig: ConsensusConfig,
      networkSetting: NetworkSetting,
      miningSetting: MiningSetting,
      memPoolSetting: MemPoolSetting,
      logConfig: LogConfig
  ): AllHandlers = {
    val flowProps = FlowHandler.props(blockFlow)
    val flowHandler =
      ActorRefT.build[FlowHandler.Command](system, flowProps, s"FlowHandler$namePostfix")
    buildWithFlowHandler(system, blockFlow, flowHandler, eventBus, namePostfix, storages)
  }
  // scalastyle:on parameter.number

  // scalastyle:off parameter.number
  def buildWithFlowHandler(
      system: ActorSystem,
      blockFlow: BlockFlow,
      flowHandler: ActorRefT[FlowHandler.Command],
      eventBus: ActorRefT[EventBus.Message],
      namePostfix: String,
      storages: Storages
  )(implicit
      brokerConfig: BrokerConfig,
      consensusConfig: ConsensusConfig,
      networkSetting: NetworkSetting,
      miningSetting: MiningSetting,
      memPoolSetting: MemPoolSetting,
      logConfig: LogConfig
  ): AllHandlers = {
    val txProps   = TxHandler.props(blockFlow, storages.pendingTxStorage, storages.readyTxStorage)
    val txHandler = ActorRefT.build[TxHandler.Command](system, txProps, s"TxHandler$namePostfix")
    val blockHandlers  = buildBlockHandlers(system, blockFlow, eventBus, namePostfix)
    val headerHandlers = buildHeaderHandlers(system, blockFlow, namePostfix)

    val dependencyHandlerProps = DependencyHandler.props(blockFlow, blockHandlers, headerHandlers)
    val dependencyHandler = ActorRefT
      .build[DependencyHandler.Command](
        system,
        dependencyHandlerProps,
        s"DependencyHandler$namePostfix"
      )

    val viewHandlerProps = ViewHandler.props(blockFlow, txHandler).withDispatcher(MiningDispatcher)
    val viewHandler      = ActorRefT.build[ViewHandler.Command](system, viewHandlerProps)

    AllHandlers(
      flowHandler,
      txHandler,
      dependencyHandler,
      viewHandler,
      blockHandlers,
      headerHandlers
    )
  }
  // scalastyle:on parameter.number

  private def buildBlockHandlers(
      system: ActorSystem,
      blockFlow: BlockFlow,
      eventBus: ActorRefT[EventBus.Message],
      namePostfix: String
  )(implicit
      brokerConfig: BrokerConfig,
      consensusConfig: ConsensusConfig,
      networkSetting: NetworkSetting,
      logConfig: LogConfig
  ): Map[ChainIndex, ActorRefT[BlockChainHandler.Command]] = {
    val handlers = for {
      from <- 0 until brokerConfig.groups
      to   <- 0 until brokerConfig.groups
      chainIndex = ChainIndex.unsafe(from, to)
      if chainIndex.relateTo(brokerConfig)
    } yield {
      val handler = ActorRefT.build[BlockChainHandler.Command](
        system,
        BlockChainHandler.props(blockFlow, chainIndex, eventBus),
        s"BlockChainHandler-$from-$to$namePostfix"
      )
      chainIndex -> handler
    }
    handlers.toMap
  }

  private def buildHeaderHandlers(
      system: ActorSystem,
      blockFlow: BlockFlow,
      namePostfix: String
  )(implicit
      brokerConfig: BrokerConfig,
      consensusConfig: ConsensusConfig
  ): Map[ChainIndex, ActorRefT[HeaderChainHandler.Command]] = {
    val headerHandlers = for {
      from <- 0 until brokerConfig.groups
      to   <- 0 until brokerConfig.groups
      chainIndex = ChainIndex.unsafe(from, to)
      if !chainIndex.relateTo(brokerConfig)
    } yield {
      val headerHander = ActorRefT.build[HeaderChainHandler.Command](
        system,
        HeaderChainHandler.props(blockFlow, chainIndex),
        s"HeaderChainHandler-$from-$to$namePostfix"
      )
      chainIndex -> headerHander
    }
    headerHandlers.toMap
  }
}
