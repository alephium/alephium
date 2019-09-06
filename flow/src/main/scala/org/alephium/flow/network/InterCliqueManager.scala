package org.alephium.flow.network

import scala.concurrent.duration._

import akka.actor.{ActorRef, Props}
import akka.io.Tcp

import org.alephium.flow.PlatformConfig
import org.alephium.flow.network.clique.{InboundBrokerHandler, OutboundBrokerHandler}
import org.alephium.flow.storage.AllHandlers
import org.alephium.protocol.model.{CliqueId, CliqueInfo}
import org.alephium.util.BaseActor

object InterCliqueManager {
  def props(selfCliqueInfo: CliqueInfo, allHandlers: AllHandlers, discoveryServer: ActorRef)(
      implicit config: PlatformConfig): Props =
    Props(new InterCliqueManager(selfCliqueInfo, allHandlers, discoveryServer))
}

class InterCliqueManager(selfCliqueInfo: CliqueInfo,
                         allHandlers: AllHandlers,
                         discoveryServer: ActorRef)(implicit config: PlatformConfig)
    extends BaseActor {
  // TODO: consider cliques with different brokerNum
  val brokers = collection.mutable.HashMap.empty[CliqueId, ActorRef]

  discoveryServer ! DiscoveryServer.GetPeerCliques

  override def receive: Receive = handleMessage orElse handleConnection orElse awaitPeerCliques

  def awaitPeerCliques: Receive = {
    case DiscoveryServer.PeerCliques(peerCliques) =>
      if (peerCliques.nonEmpty) {
        log.debug(s"Got ${peerCliques.length} from discovery server")
        peerCliques.foreach(clique => if (!brokers.contains(clique.id)) connect(clique))
      } else {
        // TODO: refine the condition, check the number of brokers for example
        if (config.bootstrap.nonEmpty) {
          scheduleOnce(discoveryServer, DiscoveryServer.GetPeerCliques, 2.second)
        }
      }
  }

  def handleConnection: Receive = {
    case c: Tcp.Connected =>
      val name  = BaseActor.envalidActorName(s"InboundBrokerHandler-${c.remoteAddress}")
      val props = InboundBrokerHandler.props(selfCliqueInfo, c.remoteAddress, sender(), allHandlers)
      context.actorOf(props, name)
      ()
    case CliqueManager.Connected(cliqueId, brokerInfo) =>
      log.debug(s"Connected to: $cliqueId, $brokerInfo")
      if (config.brokerInfo.intersect(brokerInfo)) {
        brokers += cliqueId -> sender()
      } else {
        context stop sender()
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
    case message: CliqueManager.BroadCastHeader =>
      brokers.foreach {
        case (cliqueId, broker) =>
          if (!message.origin.isFrom(cliqueId)) {
            broker ! message.headerMsg
          }
      }
  }

  def connect(cliqueInfo: CliqueInfo): Unit = {
    cliqueInfo.brokers.foreach { brokerInfo =>
      if (config.brokerInfo.intersect(brokerInfo)) {
        log.debug(s"Try to connect to $brokerInfo")
        val remoteCliqueId = cliqueInfo.id
        val name =
          BaseActor.envalidActorName(s"OutboundBrokerHandler-$remoteCliqueId-$brokerInfo")
        val props =
          OutboundBrokerHandler.props(selfCliqueInfo, remoteCliqueId, brokerInfo, allHandlers)
        context.actorOf(props, name)
      }
    }
  }
}
