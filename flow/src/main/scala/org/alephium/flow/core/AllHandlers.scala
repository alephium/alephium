package org.alephium.flow.core

import akka.actor.ActorSystem

import org.alephium.flow.network.CliqueManager
import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.model.ChainIndex
import org.alephium.util.{ActorRefT, EventBus}

final case class AllHandlers(
    flowHandler: ActorRefT[FlowHandler.Command],
    txHandler: ActorRefT[TxHandler.Command],
    blockHandlers: Map[ChainIndex, ActorRefT[BlockChainHandler.Command]],
    headerHandlers: Map[ChainIndex, ActorRefT[HeaderChainHandler.Command]])(
    implicit config: PlatformConfig) {
  def orderedHandlers: Seq[ActorRefT[_]] = {
    (blockHandlers.values ++ headerHandlers.values ++ Seq(txHandler, flowHandler)).toSeq
  }

  def getBlockHandler(chainIndex: ChainIndex): ActorRefT[BlockChainHandler.Command] = {
    assert(chainIndex.relateTo(config.brokerInfo))
    blockHandlers(chainIndex)
  }

  def getHeaderHandler(chainIndex: ChainIndex): ActorRefT[HeaderChainHandler.Command] = {
    assert(!chainIndex.relateTo(config.brokerInfo))
    headerHandlers(chainIndex)
  }
}

object AllHandlers {
  def build(system: ActorSystem,
            cliqueManager: ActorRefT[CliqueManager.Command],
            blockFlow: BlockFlow,
            eventBus: ActorRefT[EventBus.Message])(implicit config: PlatformConfig): AllHandlers = {
    val flowProps   = FlowHandler.props(blockFlow, eventBus)
    val flowHandler = ActorRefT.build[FlowHandler.Command](system, flowProps, "FlowHandler")
    buildWithFlowHandler(system, cliqueManager, blockFlow, flowHandler)
  }
  def buildWithFlowHandler(
      system: ActorSystem,
      cliqueManager: ActorRefT[CliqueManager.Command],
      blockFlow: BlockFlow,
      flowHandler: ActorRefT[FlowHandler.Command])(implicit config: PlatformConfig): AllHandlers = {
    val txProps        = TxHandler.props(blockFlow, cliqueManager)
    val txHandler      = ActorRefT.build[TxHandler.Command](system, txProps, "TxHandler")
    val blockHandlers  = buildBlockHandlers(system, cliqueManager, blockFlow, flowHandler)
    val headerHandlers = buildHeaderHandlers(system, blockFlow, flowHandler)
    AllHandlers(flowHandler, txHandler, blockHandlers, headerHandlers)
  }

  private def buildBlockHandlers(system: ActorSystem,
                                 cliqueManager: ActorRefT[CliqueManager.Command],
                                 blockFlow: BlockFlow,
                                 flowHandler: ActorRefT[FlowHandler.Command])(
      implicit config: PlatformConfig): Map[ChainIndex, ActorRefT[BlockChainHandler.Command]] = {
    val handlers = for {
      from <- 0 until config.groups
      to   <- 0 until config.groups
      chainIndex = ChainIndex.unsafe(from, to)
      if chainIndex.relateTo(config.brokerInfo)
    } yield {
      val handler = ActorRefT.build[BlockChainHandler.Command](
        system,
        BlockChainHandler.props(blockFlow, chainIndex, cliqueManager, flowHandler),
        s"BlockChainHandler-$from-$to")
      chainIndex -> handler
    }
    handlers.toMap
  }

  private def buildHeaderHandlers(system: ActorSystem,
                                  blockFlow: BlockFlow,
                                  flowHandler: ActorRefT[FlowHandler.Command])(
      implicit config: PlatformConfig): Map[ChainIndex, ActorRefT[HeaderChainHandler.Command]] = {
    val headerHandlers = for {
      from <- 0 until config.groups
      to   <- 0 until config.groups
      chainIndex = ChainIndex.unsafe(from, to)
      if !chainIndex.relateTo(config.brokerInfo)
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
