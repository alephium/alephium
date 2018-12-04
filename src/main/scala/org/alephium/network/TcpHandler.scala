package org.alephium.network

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import akka.io.Tcp
import org.alephium.protocol.message.NetworkMessage

object TcpHandler {
  sealed trait Command
  case object Start extends Command
}

trait TcpHandler extends MessageHandler {

  def remote: InetSocketAddress

  def awaitStart(connection: ActorRef): Receive = {
    case TcpHandler.Start =>
      context.become(handle(connection))
      self ! MessageHandler.SendPing
  }

  def handle(connection: ActorRef): Receive =
    handleEvent(connection) orElse handleCommand(connection) orElse forMessageHandler(connection)

  def handleEvent(connection: ActorRef): Receive = {
    case Tcp.Received(data) =>
      val message = NetworkMessage.deserializer.deserialize(data).get
      logger.debug(s"Received $message from $remote")
      handleMessage(message, connection)
    case closeEvent @ (Tcp.ConfirmedClosed | Tcp.Closed | Tcp.Aborted | Tcp.PeerClosed) =>
      logger.debug(s"Connection closed: $closeEvent")
      context stop self
  }

  def handleCommand(connection: ActorRef): Receive = {
    case message: NetworkMessage =>
      connection ! envelope(message)
  }
}

object SimpleTcpHandler {
  def props(remote: InetSocketAddress, connection: ActorRef): Props =
    Props(new SimpleTcpHandler(remote, connection))
}

case class SimpleTcpHandler(remote: InetSocketAddress, connection: ActorRef) extends TcpHandler {
  override def receive: Receive = awaitStart(connection)
}
