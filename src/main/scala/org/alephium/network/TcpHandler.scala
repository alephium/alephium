package org.alephium.network

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import akka.io.Tcp
import akka.util.ByteString
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

  protected val messageHandler = context.actorOf(MessageHandler.props(connection, blockPool))

  override def receive: Receive = awaitStart

  def awaitStart: Receive = {
    case TcpHandler.Start =>
      messageHandler ! MessageHandler.SendPing
      context become (handleEvent orElse handleOutMessage)
  }

  def handleEvent: Receive = {
    case Tcp.Received(data) =>
      // We assume each packet contains several complete messages
      // TODO: remove this assumption
      val messages = sequentialDeserialize(data)
      messages.foreach { message =>
        log.debug(s"Received message of #${message.header.cmdCode} from $remote")
        messageHandler ! message.payload
      }
    case closeEvent @ (Tcp.ConfirmedClosed | Tcp.Closed | Tcp.Aborted | Tcp.PeerClosed) =>
      log.debug(s"Connection closed: $closeEvent")
      context stop self
    case Tcp.ErrorClosed(cause) =>
      log.debug(s"Connection closed with error: $cause")
      context stop self
  }

  def handleOutMessage: Receive = {
    case message: Message =>
      connection ! TcpHandler.envelope(message)
  }

  private def sequentialDeserialize(data: ByteString): List[Message] = {
    // TODO: safe code; optimized code
    Message.deserializer._deserialize(data).get match {
      case (message, restData) =>
        if (restData.isEmpty) List(message)
        else message :: sequentialDeserialize(restData)
    }
  }
}
