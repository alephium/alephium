package org.alephium.flow.network

import akka.actor.{ActorRef, Props}
import akka.io.Tcp

import org.alephium.flow.core.AllHandlers
import org.alephium.flow.network.clique.BrokerHandler
import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.model.{BrokerInfo, CliqueInfo}
import org.alephium.util.BaseActor

object IntraCliqueManager {
  def props(builder: BrokerHandler.Builder,
            cliqueInfo: CliqueInfo,
            allHandlers: AllHandlers,
            cliqueManager: ActorRef)(implicit config: PlatformConfig): Props =
    Props(new IntraCliqueManager(builder, cliqueInfo, allHandlers, cliqueManager))

  sealed trait Command
  case object GetPeers extends Command

  sealed trait Event
  case object Ready extends Event
}

class IntraCliqueManager(builder: BrokerHandler.Builder,
                         cliqueInfo: CliqueInfo,
                         allHandlers: AllHandlers,
                         cliqueManager: ActorRef)(implicit config: PlatformConfig)
    extends BaseActor {

  cliqueInfo.brokers.foreach { remoteBroker =>
    if (remoteBroker.id > config.brokerInfo.id) {
      val address = remoteBroker.address
      log.debug(s"Connect to broker $remoteBroker")
      val props = builder.createOutboundBrokerHandler(cliqueInfo,
                                                      cliqueInfo.id,
                                                      remoteBroker,
                                                      allHandlers,
                                                      self)
      context.actorOf(props, BaseActor.envalidActorName(s"OutboundBrokerHandler-$address"))
    }
  }

  override def receive: Receive = awaitBrokers(Map.empty)

  // TODO: replace Map with Array for performance
  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def awaitBrokers(brokers: Map[Int, (BrokerInfo, ActorRef)]): Receive = {
    case Tcp.Connected(remote, _) =>
      log.debug(s"Connection from $remote")
      val index = cliqueInfo.peers.indexWhere(_ == remote)
      if (index < config.brokerInfo.id) {
        // Note: index == -1 is also the right condition
        log.debug(s"Inbound connection: $remote")
        val name = BaseActor.envalidActorName(s"InboundBrokerHandler-$remote")
        val props =
          builder.createInboundBrokerHandler(cliqueInfo, remote, sender(), allHandlers, self)
        context.actorOf(props, name)
        ()
      }
    case CliqueManager.Connected(cliqueId, brokerInfo) =>
      if (cliqueId == cliqueInfo.id) {
        log.debug(s"Broker connected: $brokerInfo")
        val newBrokers = brokers + (brokerInfo.id -> (brokerInfo -> sender()))
        if (newBrokers.size == cliqueInfo.peers.length - 1) {
          log.debug("All Brokers connected")
          cliqueManager ! IntraCliqueManager.Ready
          context become handle(newBrokers)
        } else {
          context become awaitBrokers(newBrokers)
        }
      }
  }

  def handle(brokers: Map[Int, (BrokerInfo, ActorRef)]): Receive = {
    case CliqueManager.BroadCastBlock(block, blockMsg, headerMsg, origin) =>
      assert(block.chainIndex.relateTo(config.brokerInfo))
      log.debug(s"Broadcasting block ${block.shortHex} for ${block.chainIndex}")
      // TODO: optimize this without using iteration
      brokers.foreach {
        case (_, (info, broker)) =>
          if (!origin.isFrom(cliqueInfo.id, info)) {
            if (block.chainIndex.relateTo(info)) {
              log.debug(s"Send block ${block.shortHex} to broker $info")
              broker ! blockMsg
            } else {
              log.debug(s"Send header ${block.shortHex} to broker $info")
              broker ! headerMsg
            }
          }
      }
  }
}
