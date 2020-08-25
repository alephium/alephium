package org.alephium.flow.network.broker

import java.net.InetSocketAddress

import akka.actor.Props
import akka.io.Tcp

import org.alephium.flow.network.TcpServer
import org.alephium.util.{ActorRefT, BaseActor}

object BrokerManager {
  def props(): Props = Props(new BrokerManager())

  sealed trait Command
  final case class ConfirmConnection(c: Tcp.Connected, connection: ActorRefT[Tcp.Command])
      extends Command
  final case class Remove(remote: InetSocketAddress) extends Command

  sealed trait MisBehavior extends Command {
    def remoteAddress: InetSocketAddress
  }
  sealed trait Critical  extends MisBehavior
  sealed trait Error     extends MisBehavior
  sealed trait Warning   extends MisBehavior
  sealed trait Uncertain extends MisBehavior

  final case class InvalidPingPong(remoteAddress: InetSocketAddress) extends Critical
  final case class Spamming(remoteAddress: InetSocketAddress)        extends Error
  final case class RequestTimeout(remoteAddress: InetSocketAddress)  extends Uncertain
}

class BrokerManager() extends BaseActor {
  import BrokerManager._

  def isBanned(remote: InetSocketAddress): Boolean = {
    log.debug(s"Ban $remote")
    false
  }

  def remove(remote: InetSocketAddress): Unit = {
    log.debug(s"Remove $remote")
    ()
  }

  override def receive: Receive = {
    case ConfirmConnection(c, connection) =>
      if (isBanned(c.remoteAddress)) sender() ! TcpServer.ConnectionDenied(c, connection)
      else sender() ! TcpServer.ConnectionConfirmed(c, connection)
    case Remove(remote) =>
      remove(remote)
    case misBehavior: MisBehavior =>
      log.debug(s"Misbehavior: $misBehavior")
  }
}
