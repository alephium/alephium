package org.alephium.flow.network

import akka.actor.{ActorRef, Props}
import akka.io.Tcp
import akka.util.ByteString

import org.alephium.flow.core.AllHandlers
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.clique.BrokerHandler
import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.model._
import org.alephium.util.{ActorRefT, AVector, BaseActor}

object CliqueManager {
  def props(builder: BrokerHandler.Builder, discoveryServer: ActorRefT[DiscoveryServer.Command])(
      implicit config: PlatformConfig): Props =
    Props(new CliqueManager(builder, discoveryServer))

  trait Command
  final case class Start(cliqueInfo: CliqueInfo) extends Command
  final case class BroadCastBlock(
      block: Block,
      blockMsg: ByteString,
      headerMsg: ByteString,
      origin: DataOrigin
  ) extends Command
  final case class BroadCastTx(tx: Transaction,
                               txMsg: ByteString,
                               chainIndex: ChainIndex,
                               origin: DataOrigin)
      extends Command
  final case class SendAllHandlers(allHandlers: AllHandlers)           extends Command
  final case class Syncing(cliqueId: CliqueId, brokerInfo: BrokerInfo) extends Command
  final case class Synced(cliqueId: CliqueId, brokerInfo: BrokerInfo)  extends Command
}

class CliqueManager(
    builder: BrokerHandler.Builder,
    discoveryServer: ActorRefT[DiscoveryServer.Command])(implicit config: PlatformConfig)
    extends BaseActor {
  import CliqueManager._

  type ConnectionPool = AVector[(ActorRef, Tcp.Connected)]

  var allHandlers: AllHandlers = _

  override def receive: Receive = awaitAllHandlers

  def awaitAllHandlers: Receive = {
    case SendAllHandlers(_allHandlers) =>
      allHandlers = _allHandlers
      context become awaitStart(AVector.empty)
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def awaitStart(pool: ConnectionPool): Receive = {
    case Start(cliqueInfo) =>
      log.debug("Start intra and inter clique managers")
      val intraCliqueManager =
        context.actorOf(IntraCliqueManager.props(builder, cliqueInfo, allHandlers, ActorRefT(self)),
                        "IntraCliqueManager")
      pool.foreach {
        case (connection, message) =>
          intraCliqueManager.tell(message, connection)
      }
      context become awaitIntraCliqueReady(intraCliqueManager, cliqueInfo)
    case c: Tcp.Connected =>
      val pair = (sender(), c)
      context become awaitStart(pool :+ pair)
  }

  def awaitIntraCliqueReady(intraCliqueManager: ActorRef, cliqueInfo: CliqueInfo): Receive = {
    case IntraCliqueManager.Ready =>
      log.debug(s"Intra clique manager is ready")
      val props              = InterCliqueManager.props(cliqueInfo, allHandlers, discoveryServer)
      val interCliqueManager = context.actorOf(props, "InterCliqueManager")
      context become handleWith(intraCliqueManager, interCliqueManager)
    case c: Tcp.Connected =>
      intraCliqueManager.forward(c)
  }

  def handleWith(intraCliqueManager: ActorRef, interCliqueManager: ActorRef): Receive = {
    case message: CliqueManager.BroadCastBlock =>
      intraCliqueManager ! message
      if (!message.origin.isSyncing) {
        interCliqueManager ! message
      }
    case message: CliqueManager.BroadCastTx =>
      interCliqueManager ! message
    case c: Tcp.Connected =>
      interCliqueManager.forward(c)
  }
}
