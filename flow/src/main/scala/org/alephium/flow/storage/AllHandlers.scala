package org.alephium.flow.storage

import akka.actor.{ActorRef, ActorSystem}
import org.alephium.flow.PlatformConfig
import org.alephium.protocol.model.ChainIndex

/*
 * @globalHandlers: actor of BlockHandler
 * @poolHandlers: actors of BlockPoolHandler
 */
case class AllHandlers(flowHandler: ActorRef,
                       blockHandlers: Map[ChainIndex, ActorRef],
                       headerHandlers: Map[ChainIndex, ActorRef])(implicit config: PlatformConfig) {

  def getBlockHandler(chainIndex: ChainIndex): ActorRef = {
    assert(chainIndex.relateTo(config.mainGroup))
    blockHandlers(chainIndex)
  }

  def getHeaderHandler(chainIndex: ChainIndex): ActorRef = {
    assert(!chainIndex.relateTo(config.mainGroup))
    headerHandlers(chainIndex)
  }
}

object AllHandlers {
  def build(system: ActorSystem, peerManager: ActorRef, blockFlow: BlockFlow)(
      implicit config: PlatformConfig): AllHandlers = {
    val flowHandler    = system.actorOf(FlowHandler.props(blockFlow, peerManager), "BlockHandler")
    val blockHandlers  = buildBlockHandlers(system, peerManager, blockFlow)
    val headerHandlers = buildHeaderHandlers(system, peerManager, blockFlow)
    AllHandlers(flowHandler, blockHandlers, headerHandlers)
  }

  private def buildBlockHandlers(system: ActorSystem, peerManager: ActorRef, blockFlow: BlockFlow)(
      implicit config: PlatformConfig): Map[ChainIndex, ActorRef] = {
    val handlers = for {
      from <- 0 until config.groups
      to   <- 0 until config.groups
      chainIndex = ChainIndex(from, to)
      if chainIndex.relateTo(config.mainGroup)
    } yield {
      val handler = system.actorOf(BlockChainHandler.props(blockFlow, chainIndex, peerManager),
                                   s"BlockChainHandler-$from-$to")
      chainIndex -> handler
    }
    handlers.toMap
  }

  private def buildHeaderHandlers(system: ActorSystem, peerManager: ActorRef, blockFlow: BlockFlow)(
      implicit config: PlatformConfig): Map[ChainIndex, ActorRef] = {
    val headerHandlers = for {
      from <- 0 until config.groups
      to   <- 0 until config.groups
      chainIndex = ChainIndex(from, to)
      if !chainIndex.relateTo(config.mainGroup)
    } yield {
      val headerHander = system.actorOf(
        HeaderChainHandler.props(blockFlow, chainIndex, peerManager),
        s"HeaderChainHandler-$from-$to")
      chainIndex -> headerHander
    }
    headerHandlers.toMap
  }
}
