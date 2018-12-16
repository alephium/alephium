package org.alephium.flow.client

import akka.actor.{ActorRef, ActorSystem}
import org.alephium.flow.PlatformConfig
import org.alephium.flow.network.{PeerManager, TcpHandler, TcpServer}
import org.alephium.flow.storage._
import org.alephium.protocol.model.{ChainIndex, PeerId}

case class Node(
    name: String,
    port: Int,
    mainGroup: Int,
    peerId: PeerId,
    system: ActorSystem,
    blockFlow: BlockFlow,
    peerManager: ActorRef,
    allHandlers: AllHandlers
)

object Node {
  type Builder = TcpHandler.Builder

  def apply(builders: Builder, name: String, mainGroup: Int, port: Int, groups: Int)(
      implicit config: PlatformConfig): Node = {

    val system    = ActorSystem(name, config.all)
    val blockFlow = BlockFlow()

    val peerManager = system.actorOf(PeerManager.props(builders), "PeerManager")
    val flowHandler = system.actorOf(FlowHandler.props(blockFlow), "BlockHandler")
    val blockHandlers = (
      for {
        from <- 0 until groups
        to   <- 0 until groups
        if from == config.mainGroup || to == config.mainGroup
      } yield {
        val index = ChainIndex(from, to)
        val handler = system.actorOf(BlockChainHandler.props(blockFlow, index, peerManager),
                                     s"BlockChainHandler-$from-$to")
        index -> handler
      }
    ).toMap
    val headerHandlers = (for {
      from <- 0 until groups
      to   <- 0 until groups
      if from != config.mainGroup && to != config.mainGroup
    } yield {
      val chainIndex = ChainIndex(from, to)
      val headerHander = system.actorOf(
        HeaderChainHandler.props(blockFlow, chainIndex, peerManager),
        s"HeaderChainHandler-$from-$to")
      chainIndex -> headerHander
    }).toMap
    val allHandlers = AllHandlers(flowHandler, blockHandlers, headerHandlers)
    val server      = system.actorOf(TcpServer.props(port, peerManager), "TcpServer")
    peerManager ! PeerManager.Set(server, allHandlers)

    Node(name, port, mainGroup, config.peerId, system, blockFlow, peerManager, allHandlers)
  }
}
