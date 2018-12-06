package org.alephium.network

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import akka.io.Tcp
import org.alephium.protocol.message.Message
import org.alephium.util.BaseActor

object TcpHandler {

  def props(remote: InetSocketAddress, connection: ActorRef, blockPool: ActorRef): Props =
    Props(new TcpHandler(remote, connection, blockPool))

  def envelope(message: Message): Tcp.Write =
    Tcp.Write(Message.serializer.serialize(message))

  sealed trait Command
  case object Start extends Command
}

class TcpHandler(remote: InetSocketAddress, connection: ActorRef, blockPool: ActorRef)
    extends BaseActor {

  private val messageHandler = context.actorOf(MessageHandler.props(connection, blockPool))

  override def receive: Receive = handleEvent orElse handleOutMessage

  def handleEvent: Receive = {
    case Tcp.Received(data) =>
      val message = Message.deserializer.deserialize(data).get
      logger.debug(s"Received $message from $remote")
      messageHandler ! message.payload
    case closeEvent @ (Tcp.ConfirmedClosed | Tcp.Closed | Tcp.Aborted | Tcp.PeerClosed) =>
      logger.debug(s"Connection closed: $closeEvent")
      context stop self
  }

  def handleOutMessage: Receive = {
    case message: Message =>
      connection ! TcpHandler.envelope(message)
  }
}
