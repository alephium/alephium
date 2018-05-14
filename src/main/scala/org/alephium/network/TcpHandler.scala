package org.alephium.network

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import akka.io.Tcp
import akka.util.ByteString
import org.alephium.protocol.message.Message
import org.alephium.util.BaseActor

import scala.util.{Failure, Success, Try}

object TcpHandler {

  def props(remote: InetSocketAddress, connection: ActorRef, blockPool: ActorRef): Props =
    Props(new TcpHandler(remote, connection, blockPool))

  def envelope(message: Message): Tcp.Write =
    Tcp.Write(Message.serializer.serialize(message))

  sealed trait Command
  case object Start extends Command

  def sequentialDeserialize(data: ByteString): Try[Seq[Message]] = {
    def iter(rest: ByteString, acc: Seq[Message]): Try[Seq[Message]] = {
      Message.deserializer._deserialize(rest).flatMap {
        case (message, newRest) =>
          if (newRest.isEmpty) Success(acc :+ message)
          else iter(newRest, acc :+ message)
      }
    }
    iter(data, Seq.empty)
  }
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
      TcpHandler.sequentialDeserialize(data) match {
        case Success(messages) =>
          messages.foreach { message =>
            log.debug(s"Received message of cmd@${message.header.cmdCode} from $remote")
            messageHandler ! message.payload
          }
        case Failure(_) =>
          log.debug(s"Received corrupted data from $remote with serde exception")
      }
    case event: Tcp.ConnectionClosed =>
      if (event.isErrorClosed) {
        log.debug(s"Connection closed with error: ${event.getErrorCause}")
      } else {
        log.debug(s"Connection closed normally: $event")
      }
      context stop self
  }

  def handleOutMessage: Receive = {
    case message: Message =>
      connection ! TcpHandler.envelope(message)
  }
}
