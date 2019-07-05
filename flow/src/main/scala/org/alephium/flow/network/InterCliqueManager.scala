package org.alephium.flow.network

import akka.actor.{ActorRef, Props}
import akka.io.Tcp
import org.alephium.flow.PlatformConfig
import org.alephium.flow.network.clique.{InboundBrokerHandler, OutboundBrokerHandler}
import org.alephium.flow.storage.AllHandlers
import org.alephium.protocol.model.{BrokerInfo, CliqueId, CliqueInfo}
import org.alephium.util.BaseActor

import scala.concurrent.duration._

object InterCliqueManager {
  def props(selfCliqueInfo: CliqueInfo, allHandlers: AllHandlers, discoveryServe: ActorRef)(
      implicit config: PlatformConfig): Props =
    Props(new InterCliqueManager(selfCliqueInfo, allHandlers, discoveryServe))
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
      cliqueInfo.peers.foreachWithIndex { (remote, index) =>
        if (config.brokerInfo.containsRaw(index)) {
          val remoteCliqueId = cliqueInfo.id
          val remoteBroker   = BrokerInfo(index, config.groupNumPerBroker, remote)
          val name =
            BaseActor.envalidActorName(s"OutboundBrokerHandler-$remoteCliqueId-$index-$remote")
          val props =
            OutboundBrokerHandler.props(selfCliqueInfo, remoteCliqueId, remoteBroker, allHandlers)
          context.actorOf(props, name)
        }
      }
    case CliqueManager.Connected(cliqueId, brokerInfo) =>
      if (config.brokerInfo.intersect(brokerInfo)) {
        brokers += cliqueId -> sender()
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
