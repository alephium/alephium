package org.alephium.flow.network

import akka.actor.{ActorRef, Props}
import akka.io.Tcp
import akka.util.ByteString

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.AllHandlers
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.broker.BrokerManager
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.flow.setting.{DiscoverySetting, NetworkSetting}
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model._
import org.alephium.util.{ActorRefT, AVector, BaseActor}

object CliqueManager {
  def props(blockflow: BlockFlow,
            allHandlers: AllHandlers,
            discoveryServer: ActorRefT[DiscoveryServer.Command],
            brokerManager: ActorRefT[BrokerManager.Command],
            blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command])(
      implicit brokerConfig: BrokerConfig,
      networkSetting: NetworkSetting,
      discoverySetting: DiscoverySetting): Props =
    Props(
      new CliqueManager(blockflow,
                        allHandlers,
                        discoveryServer,
                        brokerManager,
                        blockFlowSynchronizer))

  trait Command
  final case class Start(cliqueInfo: CliqueInfo) extends Command
  final case class BroadCastBlock(
      block: Block,
      blockMsg: ByteString,
      headerMsg: ByteString,
      origin: DataOrigin,
      isRecent: Boolean
  ) extends Command
  final case class BroadCastTx(tx: Transaction,
                               txMsg: ByteString,
                               chainIndex: ChainIndex,
                               origin: DataOrigin)
      extends Command
  final case class HandShaked(brokerInfo: BrokerInfo) extends Command
  final case class Synced(brokerInfo: BrokerInfo)     extends Command
  final case object IsSelfCliqueReady                 extends Command
}

class CliqueManager(blockflow: BlockFlow,
                    allHandlers: AllHandlers,
                    discoveryServer: ActorRefT[DiscoveryServer.Command],
                    brokerManager: ActorRefT[BrokerManager.Command],
                    blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command])(
    implicit brokerConfig: BrokerConfig,
    networkSetting: NetworkSetting,
    discoverySetting: DiscoverySetting)
    extends BaseActor {
  import CliqueManager._

  type ConnectionPool = AVector[(ActorRef, Tcp.Connected)]

  var selfCliqueReady: Boolean = false

  override def preStart(): Unit = {
    super.preStart()
    require(context.system.eventStream.subscribe(self, classOf[BroadCastTx]))
    require(context.system.eventStream.subscribe(self, classOf[BroadCastBlock]))
  }

  override def receive: Receive = awaitStart(AVector.empty) orElse isSelfCliqueSynced

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def awaitStart(pool: ConnectionPool): Receive = {
    case Start(cliqueInfo) =>
      log.debug("Start intra and inter clique managers")
      discoveryServer ! DiscoveryServer.SendCliqueInfo(cliqueInfo)

      val intraCliqueManager =
        context.actorOf(IntraCliqueManager.props(cliqueInfo,
                                                 blockflow,
                                                 allHandlers,
                                                 ActorRefT(self),
                                                 brokerManager,
                                                 blockFlowSynchronizer),
                        "IntraCliqueManager")
      pool.foreach {
        case (connection, message) =>
          intraCliqueManager.tell(message, connection)
      }
      context become (awaitIntraCliqueReady(intraCliqueManager, cliqueInfo) orElse isSelfCliqueSynced)
    case c: Tcp.Connected =>
      val pair = (sender(), c)
      context become (awaitStart(pool :+ pair) orElse isSelfCliqueSynced)
  }

  def awaitIntraCliqueReady(intraCliqueManager: ActorRef, cliqueInfo: CliqueInfo): Receive = {
    case IntraCliqueManager.Ready =>
      log.debug(s"Intra clique manager is ready")
      val props = InterCliqueManager.props(cliqueInfo,
                                           blockflow,
                                           allHandlers,
                                           discoveryServer,
                                           blockFlowSynchronizer)
      val interCliqueManager = context.actorOf(props, "InterCliqueManager")
      selfCliqueReady = true
      context become (handleWith(intraCliqueManager, interCliqueManager) orElse isSelfCliqueSynced)
    case c: Tcp.Connected =>
      intraCliqueManager.forward(c)
  }

  def handleWith(intraCliqueManager: ActorRef, interCliqueManager: ActorRef): Receive = {
    case message: CliqueManager.BroadCastBlock =>
      intraCliqueManager ! message
      if (message.isRecent) {
        interCliqueManager ! message
      }
    case message: CliqueManager.BroadCastTx =>
      interCliqueManager ! message

    case message @ InterCliqueManager.GetSyncStatuses =>
      interCliqueManager.forward(message)

    case c: Tcp.Connected =>
      interCliqueManager.forward(c)
  }

  def isSelfCliqueSynced: Receive = {
    case IsSelfCliqueReady => sender() ! selfCliqueReady
  }
}
