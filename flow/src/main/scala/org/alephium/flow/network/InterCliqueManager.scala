package org.alephium.flow.network

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import akka.io.Tcp
import org.alephium.flow.PlatformConfig
import org.alephium.flow.network.clique.{InboundBrokerHandler, OutboundBrokerHandler}
import org.alephium.flow.storage.AllHandlers
import org.alephium.protocol.model.{BrokerId, CliqueId, CliqueInfo}
import org.alephium.util.BaseActor

object InterCliqueManager {
  def props(selfCliqueInfo: CliqueInfo, allHandlers: AllHandlers)(
      implicit config: PlatformConfig): Props =
    Props(new InterCliqueManager(selfCliqueInfo, allHandlers))

  sealed trait Command
  case class Connect(cliqueId: CliqueId, brokerId: BrokerId, remote: InetSocketAddress)
      extends Command
}

class InterCliqueManager(selfCliqueInfo: CliqueInfo, allHandlers: AllHandlers)(
    implicit config: PlatformConfig)
    extends BaseActor {
  val brokers = collection.mutable.Set.empty[ActorRef]

  override def receive: Receive = {
    case c: Tcp.Connected =>
      val name = BaseActor.envalidActorName(s"InboundBrokerHandler-${c.remoteAddress}")
      context.actorOf(InboundBrokerHandler.props(selfCliqueInfo, sender(), allHandlers), name)
      ()
    case CliqueManager.Connect(cliqueId, brokerId, remote) =>
      val name  = BaseActor.envalidActorName(s"OutboundBrokerHandler-$cliqueId-$brokerId-$remote")
      val props = OutboundBrokerHandler.props(selfCliqueInfo, brokerId, remote, allHandlers)
      context.actorOf(props, name)
      ()
    case CliqueManager.Connected(cliqueInfo, brokerId) =>
      if (config.brokerId.intersect(cliqueInfo, brokerId)) {
        brokers += sender()
      }
    case message: CliqueManager.BroadCastBlock =>
      brokers.foreach(_ ! message.blockMsg)
  }
}
