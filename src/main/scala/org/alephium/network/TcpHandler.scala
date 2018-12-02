package org.alephium.network

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import akka.io.Tcp
import org.alephium.primitive.BlockHeader
import org.alephium.util.BaseActor
import org.alephium.serde._

trait TcpHandler extends BaseActor {

  def remoteAddress: InetSocketAddress

  def handle(connection: ActorRef): Receive =
    handleEvent orElse handleCommand(connection)

  def handleEvent: Receive = {
    case Tcp.Received(data) =>
      val bh = deserialize[BlockHeader](data).get
      logger.debug(s"Received $bh from $remoteAddress")
    case closeEvent @ (Tcp.ConfirmedClosed | Tcp.Closed | Tcp.Aborted | Tcp.PeerClosed) =>
      logger.debug(s"Connection closed: $closeEvent")
      context stop self
  }

  def handleCommand(connection: ActorRef): Receive = {
    case bh: BlockHeader =>
      connection ! Tcp.Write(serialize(bh))
  }
}

object SimpleTcpHandler {
  def props(remote: InetSocketAddress, connection: ActorRef): Props =
    Props(new SimpleTcpHandler(remote, connection))
}

class SimpleTcpHandler(remote: InetSocketAddress, connection: ActorRef) extends TcpHandler {
  override def remoteAddress: InetSocketAddress = remote

  override def receive: Receive = handle(connection)
}
