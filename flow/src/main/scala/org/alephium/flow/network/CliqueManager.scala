package org.alephium.flow.network

import akka.actor.{ActorRef, Props}
import akka.io.Tcp
import akka.util.ByteString

import org.alephium.flow.core.AllHandlers
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.clique.BrokerHandler
import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.model._
import org.alephium.util.{AVector, BaseActor}

object CliqueManager {
  def props(builder: BrokerHandler.Builder, discoveryServer: ActorRef)(
      implicit config: PlatformProfile): Props =
    Props(new CliqueManager(builder, discoveryServer))

  sealed trait Command
  case class Start(cliqueInfo: CliqueInfo) extends Command
  case class BroadCastBlock(
      block: Block,
      blockMsg: ByteString,
      headerMsg: ByteString,
      origin: DataOrigin
  ) extends Command
  case class BroadCastHeader(
      header: BlockHeader,
      headerMsg: ByteString,
      origin: DataOrigin
  ) extends Command

  sealed trait Event
  case class Connected(cliqueId: CliqueId, brokerInfo: BrokerInfo) extends Command
}

class CliqueManager(builder: BrokerHandler.Builder, discoveryServer: ActorRef)(
    implicit config: PlatformProfile)
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
      interCliqueManager ! message
    case message: CliqueManager.BroadCastHeader =>
      intraCliqueManager ! message
      interCliqueManager ! message
    case c: Tcp.Connected =>
      interCliqueManager.forward(c)
  }
}
