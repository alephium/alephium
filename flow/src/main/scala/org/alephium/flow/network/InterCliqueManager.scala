package org.alephium.flow.network

import akka.actor.{ActorRef, Props}
import akka.io.Tcp

import org.alephium.flow.core.AllHandlers
import org.alephium.flow.network.clique.{InboundBrokerHandler, OutboundBrokerHandler}
import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.model.{CliqueId, CliqueInfo}
import org.alephium.util.{BaseActor, Duration}

object InterCliqueManager {
  def props(selfCliqueInfo: CliqueInfo, allHandlers: AllHandlers, discoveryServer: ActorRef)(
      implicit config: PlatformProfile): Props =
    Props(new InterCliqueManager(selfCliqueInfo, allHandlers, discoveryServer))

  sealed trait Command
  case class Syncing(cliqueId: CliqueId) extends Command
  case class Synced(cliqueId: CliqueId)  extends Command

  case class BrokerState(actor: ActorRef, isSynced: Boolean) {
    def setSyncing(): BrokerState = BrokerState(actor, isSynced = false)

    def setSynced(): BrokerState = BrokerState(actor, isSynced = true)
  }
}

class InterCliqueManager(selfCliqueInfo: CliqueInfo,
                         allHandlers: AllHandlers,
                         discoveryServer: ActorRef)(implicit config: PlatformProfile)
    extends BaseActor
    with InterCliqueManagerState {
  discoveryServer ! DiscoveryServer.GetPeerCliques

  override def receive: Receive = handleMessage orElse handleConnection orElse awaitPeerCliques

  def awaitPeerCliques: Receive = {
    case DiscoveryServer.PeerCliques(peerCliques) =>
      if (peerCliques.nonEmpty) {
        log.debug(s"Got ${peerCliques.length} from discovery server")
        peerCliques.foreach(clique => if (!containsBroker(clique)) connect(clique))
      } else {
        // TODO: refine the condition, check the number of brokers for example
        if (config.bootstrap.nonEmpty) {
          scheduleOnce(discoveryServer, DiscoveryServer.GetPeerCliques, Duration.ofSecondsUnsafe(2))
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
        addBroker(cliqueId, sender())
      } else {
        context stop sender()
      }
  }

  def handleMessage: Receive = {
    case message: CliqueManager.BroadCastBlock =>
      val block = message.block
      log.debug(s"Broadcasting block ${block.shortHex} for ${block.chainIndex}")
      iterBrokers {
        case (cliqueId, brokerState) =>
          if (!message.origin.isFrom(cliqueId) && brokerState.isSynced) {
            log.debug(s"Send block to broker $cliqueId")
            brokerState.actor ! message.blockMsg
          }
      }
    case message: CliqueManager.BroadCastTx =>
      log.debug(s"Broadcasting tx ${message.tx.shortHex} for ${message.chainIndex}")
      iterBrokers {
        case (cliqueId, brokerState) =>
          if (!message.origin.isFrom(cliqueId) && brokerState.isSynced) {
            log.debug(s"Send tx to broker $cliqueId")
            brokerState.actor ! message.txMsg
          }
      }
    case InterCliqueManager.Syncing(cliqueId) =>
      log.debug(s"$cliqueId starts syncing")
      setSyncing(cliqueId)
    case InterCliqueManager.Synced(cliqueId) =>
      log.debug(s"$cliqueId has synced")
      setSynced(cliqueId)
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

trait InterCliqueManagerState {
  import InterCliqueManager._

  // TODO: consider cliques with different brokerNum
  private val brokers = collection.mutable.HashMap.empty[CliqueId, BrokerState]

  def addBroker(cliqueId: CliqueId, broker: ActorRef): Unit = {
    assert(!brokers.contains(cliqueId))
    brokers += cliqueId -> BrokerState(broker, isSynced = false)
  }

  def containsBroker(clique: CliqueInfo): Boolean = {
    brokers.contains(clique.id)
  }

  def iterBrokers(f: ((CliqueId, BrokerState)) => Unit): Unit = {
    brokers.foreach(f)
  }

  def setSyncing(cliqueId: CliqueId): Unit = {
    assert(brokers.contains(cliqueId))
    val current = brokers(cliqueId)
    brokers(cliqueId) = current.setSyncing()
  }

  def setSynced(cliqueId: CliqueId): Unit = {
    assert(brokers.contains(cliqueId))
    val current = brokers(cliqueId)
    brokers(cliqueId) = current.setSynced()
  }
}
