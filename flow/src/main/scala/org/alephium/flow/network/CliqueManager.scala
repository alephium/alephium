package org.alephium.flow.network

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import akka.io.Tcp
import org.alephium.flow.PlatformConfig
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.storage.AllHandlers
import org.alephium.protocol.model.{Block, BrokerId, CliqueId, CliqueInfo}
import org.alephium.util.BaseActor

object CliqueManager {
  def props()(implicit config: PlatformConfig): Props =
    Props(new CliqueManager())

  sealed trait Command
  case class Start(cliqueInfo: CliqueInfo, intraCliqueManager: ActorRef) extends Command
  case class Connect(cliqueId: CliqueId, brokerId: BrokerId, remote: InetSocketAddress)
      extends Command
  case class BroadCastBlock(
      block: Block,
      blockMsg: Tcp.Write,
      headerMsg: Tcp.Write,
      origin: DataOrigin
  ) extends Command

  sealed trait Event
  case class Connected(cliqueInfo: CliqueInfo, brokerId: BrokerId) extends Command
}

class CliqueManager()(implicit config: PlatformConfig) extends BaseActor {
  import CliqueManager._

  var allHandlers: AllHandlers = _

  override def receive: Receive = awaitAllHandlers

  def awaitAllHandlers: Receive = {
    case _allHandlers: AllHandlers =>
      allHandlers = _allHandlers
      context become awaitStart
  }

  def awaitStart: Receive = {
    case Start(cliqueInfo, intraCliqueManager) =>
      val interCliqueManager =
        context.actorOf(InterCliqueManager.props(cliqueInfo, allHandlers), "InterCliqueManager")
      context.watch(intraCliqueManager)
      context become handleWith(intraCliqueManager, interCliqueManager)
  }

  def handleWith(intraCliqueManager: ActorRef, interCliqueManager: ActorRef): Receive = {
    case broadcast: CliqueManager.BroadCastBlock =>
      intraCliqueManager ! broadcast
      interCliqueManager ! broadcast
    case c: Connect =>
      interCliqueManager ! c
  }
}
