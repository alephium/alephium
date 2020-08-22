package org.alephium.flow.network

import akka.actor.{ActorRef, Props, Terminated}
import akka.io.Tcp

import org.alephium.flow.FlowMonitor
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.AllHandlers
import org.alephium.flow.network.broker._
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{BrokerInfo, CliqueInfo}
import org.alephium.util.{ActorRefT, BaseActor}

object IntraCliqueManager {
  def props(cliqueInfo: CliqueInfo,
            blockflow: BlockFlow,
            allHandlers: AllHandlers,
            cliqueManager: ActorRefT[CliqueManager.Command],
            brokerManager: ActorRefT[BrokerManager.Command],
            blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command])(
      implicit brokerConfig: BrokerConfig,
      networkSetting: NetworkSetting): Props =
    Props(
      new IntraCliqueManager(cliqueInfo,
                             blockflow,
                             allHandlers,
                             cliqueManager,
                             brokerManager,
                             blockFlowSynchronizer))

  sealed trait Command    extends CliqueManager.Command
  final case object Ready extends Command
}

class IntraCliqueManager(cliqueInfo: CliqueInfo,
                         blockflow: BlockFlow,
                         allHandlers: AllHandlers,
                         cliqueManager: ActorRefT[CliqueManager.Command],
                         brokerManager: ActorRefT[BrokerManager.Command],
                         blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command])(
    implicit brokerConfig: BrokerConfig,
    networkSetting: NetworkSetting)
    extends BaseActor {
  cliqueInfo.brokers.foreach { remoteBroker =>
    if (remoteBroker.brokerId > brokerConfig.brokerId) {
      val address = remoteBroker.address
      log.debug(s"Connect to broker $remoteBroker")
      val props = OutboundBrokerHandler.props(cliqueInfo,
                                              remoteBroker,
                                              blockflow,
                                              allHandlers,
                                              ActorRefT[CliqueManager.Command](self),
                                              brokerManager,
                                              blockFlowSynchronizer)
      context.actorOf(props, BaseActor.envalidActorName(s"OutboundBrokerHandler-$address"))
    }
  }
  checkAllSynced(Map.empty)

  override def receive: Receive = awaitBrokers(Map.empty)

  // TODO: replace Map with Array for performance
  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def awaitBrokers(brokers: Map[Int, (BrokerInfo, ActorRef)]): Receive = {
    case Tcp.Connected(remote, _) =>
      log.debug(s"Connection from $remote")
      val index = cliqueInfo.peers.indexWhere(_ == remote)
      if (index < brokerConfig.brokerId) {
        // Note: index == -1 is also the right condition
        log.debug(s"Inbound connection: $remote")
        val name = BaseActor.envalidActorName(s"InboundBrokerHandler-$remote")
        val props =
          InboundBrokerHandler.props(cliqueInfo,
                                     remote,
                                     ActorRefT[Tcp.Command](sender()),
                                     blockflow,
                                     allHandlers,
                                     ActorRefT[CliqueManager.Command](self),
                                     brokerManager,
                                     blockFlowSynchronizer)
        context.actorOf(props, name)
        ()
      }
    case CliqueManager.HandShaked(cliqueId, brokerInfo) =>
      log.debug(s"Start syncing with intra-clique node: ${brokerInfo.address}")
      if (cliqueId == cliqueInfo.id && !brokers.contains(brokerInfo.brokerId)) {
        log.debug(s"Broker connected: $brokerInfo")
        context watch sender()
        val newBrokers = brokers + (brokerInfo.brokerId -> (brokerInfo -> sender()))
        checkAllSynced(newBrokers)
      }
    case Terminated(actor) => handleTerminated(actor, brokers)
  }

  def checkAllSynced(newBrokers: Map[Int, (BrokerInfo, ActorRef)]): Unit = {
    if (newBrokers.size == cliqueInfo.peers.length - 1) {
      log.debug("All Brokers connected")
      cliqueManager ! IntraCliqueManager.Ready
      context become handle(newBrokers)
    } else {
      context become awaitBrokers(newBrokers)
    }
  }

  def handle(brokers: Map[Int, (BrokerInfo, ActorRef)]): Receive = {
    case CliqueManager.BroadCastBlock(block, blockMsg, headerMsg, origin) =>
      assume(block.chainIndex.relateTo(brokerConfig))
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
    case Terminated(actor) => handleTerminated(actor, brokers)
  }

  def handleTerminated(actor: ActorRef, brokers: Map[Int, (BrokerInfo, ActorRef)]): Unit = {
    brokers.foreach {
      case (_, (info, broker)) if broker == actor =>
        log.error(s"Clique node $info is not functioning")
        context.system.eventStream.publish(FlowMonitor.Shutdown)
      case _ => ()
    }
  }
}
