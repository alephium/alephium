package org.alephium.flow.network

import java.net.InetSocketAddress

import akka.actor.Props
import akka.event.LoggingAdapter
import akka.io.Tcp

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.AllHandlers
import org.alephium.flow.network.broker.BrokerHandler
import org.alephium.flow.network.interclique.{InboundBrokerHandler, OutboundBrokerHandler}
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.flow.setting.{DiscoverySetting, NetworkSetting}
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{BrokerInfo, ChainIndex, CliqueId, CliqueInfo}
import org.alephium.util.{ActorRefT, BaseActor, Duration}

object InterCliqueManager {
  // scalastyle:off parameter.number
  def props(selfCliqueInfo: CliqueInfo,
            blockflow: BlockFlow,
            allHandlers: AllHandlers,
            discoveryServer: ActorRefT[DiscoveryServer.Command],
            blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command])(
      implicit brokerConfig: BrokerConfig,
      networkSetting: NetworkSetting,
      discoverySetting: DiscoverySetting): Props =
    Props(
      new InterCliqueManager(selfCliqueInfo,
                             blockflow,
                             allHandlers,
                             discoveryServer,
                             blockFlowSynchronizer))
  //scalastyle:on

  sealed trait Command              extends CliqueManager.Command
  final case object GetSyncStatuses extends Command

  final case class SyncStatus(cliqueId: CliqueId, address: InetSocketAddress, isSynced: Boolean)

  final case class BrokerState(info: BrokerInfo,
                               actor: ActorRefT[BrokerHandler.Command],
                               isSynced: Boolean) {
    def setSynced(): BrokerState = BrokerState(info, actor, isSynced = true)

    def readyFor(chainIndex: ChainIndex): Boolean = {
      isSynced && info.contains(chainIndex.from)
    }
  }
}

class InterCliqueManager(selfCliqueInfo: CliqueInfo,
                         blockflow: BlockFlow,
                         allHandlers: AllHandlers,
                         discoveryServer: ActorRefT[DiscoveryServer.Command],
                         blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command])(
    implicit brokerConfig: BrokerConfig,
    networkSetting: NetworkSetting,
    discoverySetting: DiscoverySetting)
    extends BaseActor
    with InterCliqueManagerState {
  import InterCliqueManager._
  discoveryServer ! DiscoveryServer.GetNeighborCliques

  val selfBrokerInfo: BrokerInfo = selfCliqueInfo.selfBrokerInfo

  override def receive: Receive = handleMessage orElse handleConnection orElse awaitNeighborCliques

  def awaitNeighborCliques: Receive = {
    case DiscoveryServer.NeighborCliques(neighborCliques) =>
      if (neighborCliques.nonEmpty) {
        log.debug(s"Got ${neighborCliques.length} from discovery server")
        neighborCliques.foreach(clique => if (!containsBroker(clique)) connect(clique))
      } else {
        // TODO: refine the condition, check the number of brokers for example
        log.debug(s"Try to fetch peer brokers from discovery server")
        if (discoverySetting.bootstrap.nonEmpty) {
          scheduleOnce(discoveryServer.ref,
                       DiscoveryServer.GetNeighborCliques,
                       Duration.ofSecondsUnsafe(2))
          ()
        }
      }
  }

  def handleConnection: Receive = {
    case Tcp.Connected(remoteAddress, _) =>
      log.debug(s"Connected to ${remoteAddress}")
      val name = BaseActor.envalidActorName(s"InboundBrokerHandler-${remoteAddress}")
      val props =
        InboundBrokerHandler.props(
          selfCliqueInfo,
          remoteAddress,
          ActorRefT[Tcp.Command](sender()),
          blockflow,
          allHandlers,
          ActorRefT[CliqueManager.Command](self),
          blockFlowSynchronizer
        )
      context.actorOf(props, name)
      ()
    case CliqueManager.HandShaked(cliqueId, brokerInfo) =>
      log.debug(s"Start syncing with inter-clique node: $cliqueId, $brokerInfo")
      if (brokerConfig.intersect(brokerInfo)) {
        addBroker(cliqueId, brokerInfo, sender())
      } else {
        context stop sender()
      }
    case CliqueManager.Synced(cliqueId, brokerInfo) =>
      log.debug(s"Complete syncing with $cliqueId, $brokerInfo")
      setSynced(cliqueId, brokerInfo)
  }

  def handleMessage: Receive = {
    case message: CliqueManager.BroadCastBlock =>
      val block = message.block
      log.debug(s"Broadcasting block ${block.shortHex} for ${block.chainIndex}")
      iterBrokers { (cliqueId, brokerState) =>
        if (!message.origin.isFrom(cliqueId) && brokerState.readyFor(block.chainIndex)) {
          log.debug(s"Send block to broker $cliqueId")
          brokerState.actor ! BrokerHandler.Send(message.blockMsg)
        }
      }
    case message: CliqueManager.BroadCastTx =>
      log.debug(s"Broadcasting tx ${message.tx.shortHex} for ${message.chainIndex}")
      iterBrokers { (cliqueId, brokerState) =>
        if (!message.origin.isFrom(cliqueId) && brokerState.readyFor(message.chainIndex)) {
          log.debug(s"Send tx to broker $cliqueId")
          brokerState.actor ! BrokerHandler.Send(message.txMsg)
        }
      }

    case GetSyncStatuses =>
      val syncStatuses: Seq[SyncStatus] = mapBrokers { (cliqueId, brokerState) =>
        SyncStatus(cliqueId, brokerState.info.address, brokerState.isSynced)
      }
      sender() ! syncStatuses
  }

  def connect(cliqueInfo: CliqueInfo): Unit = {
    cliqueInfo.brokers.foreach { brokerInfo =>
      if (brokerConfig.intersect(brokerInfo)) {
        log.debug(s"Try to connect to ${cliqueInfo.id} $brokerInfo")
        val remoteCliqueId = cliqueInfo.id
        val name =
          BaseActor.envalidActorName(s"OutboundBrokerHandler-$remoteCliqueId-$brokerInfo")
        val props =
          OutboundBrokerHandler.props(selfCliqueInfo,
                                      brokerInfo,
                                      blockflow,
                                      allHandlers,
                                      ActorRefT[CliqueManager.Command](self),
                                      blockFlowSynchronizer)
        context.actorOf(props, name)
      }
    }
  }
}

trait InterCliqueManagerState {
  import InterCliqueManager._

  def log: LoggingAdapter

  // The key is (CliqueId, BrokerId)
  private val brokers = collection.mutable.HashMap.empty[(CliqueId, Int), BrokerState]

  def addBroker(cliqueId: CliqueId,
                brokerInfo: BrokerInfo,
                broker: ActorRefT[BrokerHandler.Command]): Unit = {
    val brokerKey = cliqueId -> brokerInfo.brokerId
    if (!brokers.contains(brokerKey)) {
      brokers += brokerKey -> BrokerState(brokerInfo, broker, isSynced = false)
    } else {
      log.warning(s"Ignore another connection from $cliqueId")
    }
  }

  def containsBroker(clique: CliqueInfo): Boolean = {
    brokers.keySet.exists(_._1 == clique.id)
  }

  def iterBrokers(f: (CliqueId, BrokerState) => Unit): Unit = {
    brokers.foreach {
      case ((cliqueId, _), state) => f(cliqueId, state)
    }
  }

  def mapBrokers[A](f: (CliqueId, BrokerState) => A): Seq[A] = {
    brokers.toSeq.map {
      case ((cliqueId, _), state) => f(cliqueId, state)
    }
  }

  def setSynced(cliqueId: CliqueId, brokerInfo: BrokerInfo): Unit = {
    val brokerKey = cliqueId -> brokerInfo.brokerId
    brokers.get(brokerKey) match {
      case Some(state) => brokers(brokerKey) = state.setSynced()
      case None        => log.warning(s"Unexpected message Synced from $cliqueId")
    }
  }
}
