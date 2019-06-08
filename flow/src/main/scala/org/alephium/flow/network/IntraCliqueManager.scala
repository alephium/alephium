package org.alephium.flow.network

import akka.actor.{ActorRef, Props}
import akka.io.{IO, Tcp}
import org.alephium.flow.PlatformConfig
import org.alephium.flow.network.clique.{BrokerHandler, InboundBrokerHandler, OutboundBrokerHandler}
import org.alephium.flow.storage.AllHandlers
import org.alephium.protocol.model.{BrokerId, CliqueInfo}
import org.alephium.util.BaseActor

object IntraCliqueManager {
  def props(builder: BrokerHandler.Builder, cliqueInfo: CliqueInfo)(
      implicit config: PlatformConfig): Props =
    Props(new IntraCliqueManager(builder, cliqueInfo))

  sealed trait Command
  case class Set(allHandlers: AllHandlers) extends Command
  case object GetPeers                     extends Command

  case class BrokerInfo(brokerId: BrokerId, brokerHandler: ActorRef)

  sealed trait Event
  case object Ready extends Event

}

class IntraCliqueManager(builder: BrokerHandler.Builder, cliqueInfo: CliqueInfo)(
    implicit config: PlatformConfig)
    extends BaseActor {
  import IntraCliqueManager._

  var allHandlers: AllHandlers = _

  override def receive: Receive = {
    case Set(_allHandlers) =>
      allHandlers = _allHandlers
      cliqueInfo.peers.foreachWithIndex {
        case (address, index) =>
          if (index >= config.brokerId.value) {
            IO(Tcp)(context.system) ! Tcp.Connect(address)
            val props = builder.createOutboundBrokerHandler(cliqueInfo,
                                                            BrokerId.unsafe(index),
                                                            address,
                                                            allHandlers)
            context.actorOf(props, BaseActor.envalidActorName(s"OutboundBrokerHandler-$address"))
          }
      }
      context become awaitBrokers(Map.empty)
  }

  // TODO: replace Map with Array for performance
  def awaitBrokers(brokers: Map[Int, ActorRef]): Receive = {
    case Tcp.Connected(remote, _) =>
      val index = cliqueInfo.peers.indexWhere(_ == remote)
      if (index >= 0 && index < config.brokerId.value) {
        val name = BaseActor.envalidActorName(s"InboundBrokerHandler-$remote")
        context.actorOf(InboundBrokerHandler.props(cliqueInfo, sender(), allHandlers), name)
      } else if (index > config.brokerId.value && index < cliqueInfo.peers.length) {
        context.actorOf(
          OutboundBrokerHandler.props(cliqueInfo, BrokerId.unsafe(index), remote, allHandlers))
      }
      ()
    case CliqueManager.Connected(_cliqueInfo, brokerId) =>
      if (_cliqueInfo.id == cliqueInfo.id) {
        val newBrokers = brokers + (brokerId.value -> sender())
        if (newBrokers.size == cliqueInfo.peers.length - 1) {
          context become handle(brokers)
        } else {
          context become awaitBrokers(newBrokers)
        }
      }
  }

  def handle(brokers: Map[Int, ActorRef]): Receive = {
    case CliqueManager.BroadCastBlock(block, blockMsg, headerMsg, _) =>
      assert(block.chainIndex.relateTo(config.brokerId))
      log.debug(s"Broadcasting block/header to peers")
      brokers.foreach {
        case (index, broker) =>
          if (block.chainIndex.relateTo(BrokerId.unsafe(index))) broker ! blockMsg
          else broker ! headerMsg
      }
  }
}
