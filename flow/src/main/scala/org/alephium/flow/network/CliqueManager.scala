package org.alephium.flow.network

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import akka.io.Tcp
import org.alephium.flow.PlatformConfig
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.clique.BrokerHandler
import org.alephium.flow.storage.AllHandlers
import org.alephium.protocol.model.{Block, BrokerId, CliqueId, CliqueInfo}
import org.alephium.util.{AVector, BaseActor}

object CliqueManager {
  def props(builder: BrokerHandler.Builder)(implicit config: PlatformConfig): Props =
    Props(new CliqueManager(builder))

  sealed trait Command
  case class Start(cliqueInfo: CliqueInfo) extends Command
  case class Connect(cliqueId: CliqueId, brokerId: BrokerId, remote: InetSocketAddress)
      extends Command
  // TODO: simplify this
  case class BroadCastBlock(
      block: Block,
      blockMsg: Tcp.Write,
      headerMsg: Tcp.Write,
      origin: DataOrigin
  ) extends Command

  sealed trait Event
  case class Connected(cliqueInfo: CliqueInfo, brokerId: BrokerId) extends Command
}

class CliqueManager(builder: BrokerHandler.Builder)(implicit config: PlatformConfig)
    extends BaseActor {
  import CliqueManager._

  type ConnectionPool = AVector[(ActorRef, Tcp.Connected)]

  var allHandlers: AllHandlers = _

  override def receive: Receive = awaitAllHandlers

  def awaitAllHandlers: Receive = {
    case _allHandlers: AllHandlers =>
      allHandlers = _allHandlers
      context become awaitStart(AVector.empty)
  }

  def awaitStart(pool: ConnectionPool): Receive = {
    case Start(cliqueInfo) =>
      log.debug("Start intra and inter clique managers")
      val intraCliqueManager =
        context.actorOf(IntraCliqueManager.props(builder, cliqueInfo, allHandlers),
                        "IntraCliqueManager")
      val interCliqueManager =
        context.actorOf(InterCliqueManager.props(cliqueInfo, allHandlers), "InterCliqueManager")
      pool.foreach {
        case (connection, message) =>
          intraCliqueManager.tell(message, connection)
      }
      context become awaitIntraCliqueReady(intraCliqueManager, interCliqueManager)
    case c: Tcp.Connected =>
      val pair = (sender(), c)
      context become awaitStart(pool :+ pair)
  }

  def awaitIntraCliqueReady(intraCliqueManager: ActorRef, interCliqueManager: ActorRef): Receive = {
    case IntraCliqueManager.Ready =>
      log.debug(s"Intra clique manager is ready")
      context become handleWith(intraCliqueManager, interCliqueManager)
    case c: Tcp.Connected =>
      intraCliqueManager.forward(c)
  }

  def handleWith(intraCliqueManager: ActorRef, interCliqueManager: ActorRef): Receive = {
    case message: CliqueManager.BroadCastBlock =>
      intraCliqueManager ! message
      interCliqueManager ! message
    case c: Connect =>
      interCliqueManager ! c
    case c: Tcp.Connected =>
      interCliqueManager.forward(c)
  }
}
