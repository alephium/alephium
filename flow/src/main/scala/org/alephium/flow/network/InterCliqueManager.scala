package org.alephium.flow.network

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import akka.io.Tcp
import org.alephium.flow.PlatformConfig
import org.alephium.flow.network.clique.{InboundBrokerHandler, OutboundBrokerHandler}
import org.alephium.flow.storage.AllHandlers
import org.alephium.protocol.model.{BrokerId, CliqueId, CliqueInfo}
import org.alephium.util.BaseActor

import scala.concurrent.duration._

object InterCliqueManager {
  def props(selfCliqueInfo: CliqueInfo, allHandlers: AllHandlers, discoveryServe: ActorRef)(
      implicit config: PlatformConfig): Props =
    Props(new InterCliqueManager(selfCliqueInfo, allHandlers, discoveryServe))

  sealed trait Command
  case class Connect(cliqueId: CliqueId, brokerId: BrokerId, remote: InetSocketAddress)
      extends Command
}

class InterCliqueManager(selfCliqueInfo: CliqueInfo,
                         allHandlers: AllHandlers,
                         discoveryServer: ActorRef)(implicit config: PlatformConfig)
    extends BaseActor {
  val brokers = collection.mutable.HashMap.empty[CliqueId, ActorRef]

  discoveryServer ! DiscoveryServer.GetPeerCliques

  override def receive: Receive = handleMessage orElse handleConnection

  def awaitPeerCliques: Receive = {
    case DiscoveryServer.PeerCliques(peers) =>
      if (peers.nonEmpty) {
        peers.foreach(peer => self ! CliqueManager.Connect(peer))
      } else {
        scheduleOnce(discoveryServer, DiscoveryServer.GetPeerCliques, 1.second)
      }
  }

  def handleConnection: Receive = {
    case c: Tcp.Connected =>
      val name = BaseActor.envalidActorName(s"InboundBrokerHandler-${c.remoteAddress}")
      context.actorOf(InboundBrokerHandler.props(selfCliqueInfo, sender(), allHandlers), name)
      ()
    case CliqueManager.Connect(cliqueInfo) =>
      val peerConfig = cliqueInfo.cliqueConfig
      cliqueInfo.peers.foreachWithIndex { (remote, index) =>
        if (config.brokerId.containsRaw(index)(peerConfig)) {
          val cliqueId = cliqueInfo.id
          val name     = BaseActor.envalidActorName(s"OutboundBrokerHandler-$cliqueId-$index-$remote")
          val props    = OutboundBrokerHandler.props(cliqueInfo, index, remote, allHandlers)
          context.actorOf(props, name)
        }
      }
    case CliqueManager.Connected(cliqueInfo, brokerId) =>
      if (config.brokerId.intersect(cliqueInfo, brokerId)) {
        brokers += cliqueInfo.id -> sender()
      }
  }

  def handleMessage: Receive = {
    case message: CliqueManager.BroadCastBlock =>
      brokers.foreach {
        case (cliqueId, broker) =>
          if (!message.origin.isFrom(cliqueId)) {
            broker ! message.blockMsg
          }
      }
  }
}
