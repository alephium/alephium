package org.alephium.flow.network.broker

import java.net.InetSocketAddress

import akka.actor.Props
import akka.io.Tcp

import org.alephium.flow.network.TcpController
import org.alephium.util.{ActorRefT, BaseActor}

object BrokerManager {
  def props(): Props = Props(new BrokerManager())

  sealed trait Command
  final case class ConfirmConnection(connected: Tcp.Connected, connection: ActorRefT[Tcp.Command])
      extends Command
  final case class Remove(remote: InetSocketAddress) extends Command

  sealed trait Misbehavior extends Command {
    def remoteAddress: InetSocketAddress
  }
  sealed trait Critical  extends Misbehavior
  sealed trait Error     extends Misbehavior
  sealed trait Warning   extends Misbehavior
  sealed trait Uncertain extends Misbehavior

  final case class InvalidMessage(remoteAddress: InetSocketAddress)  extends Critical
  final case class InvalidPingPong(remoteAddress: InetSocketAddress) extends Critical
  final case class InvalidDag(remoteAddress: InetSocketAddress)      extends Critical
  final case class Spamming(remoteAddress: InetSocketAddress)        extends Warning
  final case class RequestTimeout(remoteAddress: InetSocketAddress)  extends Uncertain
}

// TODO: use broker manager for real
class BrokerManager() extends BaseActor {
  import BrokerManager._

  override def preStart(): Unit = {
    require(context.system.eventStream.subscribe(self, classOf[BrokerManager.Misbehavior]))
  }

  def isBanned(remote: InetSocketAddress): Boolean = {
    log.debug(s"Check availability $remote")
    false
  }

  def remove(remote: InetSocketAddress): Unit = {
    log.debug(s"Remove $remote")
    ()
  }

  override def receive: Receive = {
    case ConfirmConnection(connected, connection) =>
      if (isBanned(connected.remoteAddress)) {
        sender() ! TcpController.ConnectionDenied(connected, connection)
      } else {
        sender() ! TcpController.ConnectionConfirmed(connected, connection)
      }
    case Remove(remote) =>
      remove(remote)
    case misbehavior: Misbehavior =>
      log.debug(s"Misbehavior: $misbehavior")
  }
}
