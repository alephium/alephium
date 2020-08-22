package org.alephium.flow.handler

import akka.actor.ActorSystem

import org.alephium.flow.core.BlockFlow
import org.alephium.protocol.config.{BrokerConfig, ConsensusConfig}
import org.alephium.protocol.model.ChainIndex
import org.alephium.util.{ActorRefT, EventBus}

final case class AllHandlers(
    flowHandler: ActorRefT[FlowHandler.Command],
    txHandler: ActorRefT[TxHandler.Command],
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
      consensusConfig: ConsensusConfig): AllHandlers = {
    val flowProps   = FlowHandler.props(blockFlow, eventBus)
    val flowHandler = ActorRefT.build[FlowHandler.Command](system, flowProps, "FlowHandler")
    buildWithFlowHandler(system, blockFlow, flowHandler)
  }
  def buildWithFlowHandler(system: ActorSystem,
                           blockFlow: BlockFlow,
                           flowHandler: ActorRefT[FlowHandler.Command])(
      implicit brokerConfig: BrokerConfig,
      consensusConfig: ConsensusConfig): AllHandlers = {
    val txProps        = TxHandler.props(blockFlow)
    val txHandler      = ActorRefT.build[TxHandler.Command](system, txProps, "TxHandler")
    val blockHandlers  = buildBlockHandlers(system, blockFlow, flowHandler)
    val headerHandlers = buildHeaderHandlers(system, blockFlow, flowHandler)
    AllHandlers(flowHandler, txHandler, blockHandlers, headerHandlers)
  }

  private def buildBlockHandlers(system: ActorSystem,
                                 blockFlow: BlockFlow,
                                 flowHandler: ActorRefT[FlowHandler.Command])(
      implicit brokerConfig: BrokerConfig,
      consensusConfig: ConsensusConfig): Map[ChainIndex, ActorRefT[BlockChainHandler.Command]] = {
    val handlers = for {
      from <- 0 until brokerConfig.groups
      to   <- 0 until brokerConfig.groups
      chainIndex = ChainIndex.unsafe(from, to)
      if chainIndex.relateTo(brokerConfig)
    } yield {
      val handler = ActorRefT.build[BlockChainHandler.Command](
        system,
        BlockChainHandler.props(blockFlow, chainIndex, flowHandler),
        s"BlockChainHandler-$from-$to")
      chainIndex -> handler
    }
    handlers.toMap
  }

  private def buildHeaderHandlers(system: ActorSystem,
                                  blockFlow: BlockFlow,
                                  flowHandler: ActorRefT[FlowHandler.Command])(
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
        s"HeaderChainHandler-$from-$to")
      chainIndex -> headerHander
    }
    headerHandlers.toMap
  }
}
