package org.alephium.flow.network.broker

import java.net.InetSocketAddress

import akka.io.Tcp

import org.alephium.util.{ActorRefT, BaseActor}

object BrokerManager {
  sealed trait Command
  final case class ConfirmConnection(remote: InetSocketAddress, connection: ActorRefT[Tcp.Command])
      extends Command
  final case class Remove(remote: InetSocketAddress) extends Command
}

trait BrokerManager extends BaseActor {
  import BrokerManager._

  def isBanned(remote: InetSocketAddress): Boolean

  def remove(remote: InetSocketAddress): Unit

  override def receive: Receive = {
    case ConfirmConnection(remote, connection) =>
      if (isBanned(remote)) sender() ! TcpController.ConnectionDenied(remote, connection)
      else sender() ! TcpController.ConnectionConfirmed(remote, connection)
    case Remove(remote) =>
      remove(remote)
  }
}
