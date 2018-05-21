package org.alephium.network

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props, Terminated}
import akka.io.Tcp
import akka.util.ByteString
import org.alephium.protocol.message.Message
import org.alephium.serde.NotEnoughBytesException
import org.alephium.util.BaseActor

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

object TcpHandler {

  def props(remote: InetSocketAddress, connection: ActorRef, blockPool: ActorRef): Props =
    Props(new TcpHandler(remote, connection, blockPool))

  def envelope(message: Message): Tcp.Write =
    Tcp.Write(Message.serializer.serialize(message))

  def deserialize(data: ByteString): Try[(Seq[Message], ByteString)] = {
    @tailrec
    def iter(rest: ByteString, acc: Seq[Message]): Try[(Seq[Message], ByteString)] = {
      Message.deserializer._deserialize(rest) match {
        case Success((message, newRest)) =>
          iter(newRest, acc :+ message)
        case Failure(_: NotEnoughBytesException) =>
          Success((acc, rest))
        case Failure(e) =>
          Failure(e)
      }
    }
    iter(data, Seq.empty)
  }
}

class TcpHandler(remote: InetSocketAddress, connection: ActorRef, blockPool: ActorRef)
    extends BaseActor {

  val messageHandler: ActorRef = context.actorOf(MessageHandler.props(connection, blockPool))

  override def preStart(): Unit = {
    context.watch(messageHandler)
    messageHandler ! MessageHandler.SendPing
  }

  override def receive: Receive = handleEvent(ByteString.empty) orElse handleOutMessage

  def handleEvent(unaligned: ByteString): Receive = {
    case Tcp.Received(data) =>
      TcpHandler.deserialize(unaligned ++ data) match {
        case Success((messages, rest)) =>
          messages.foreach { message =>
            log.debug(s"Received message of cmd@${message.header.cmdCode} from $remote")
            messageHandler ! message.payload
          }
          context.become(handleEvent(rest) orElse handleOutMessage)
        case Failure(_) =>
          log.info(s"Received corrupted data from $remote with serde exception; Close connection")
          context stop self
      }
    case event: Tcp.ConnectionClosed =>
      if (event.isErrorClosed) {
        log.debug(s"Connection closed with error: ${event.getErrorCause}")
      } else {
        log.debug(s"Connection closed normally: $event")
      }
      context stop self
    case Terminated(child) if child == messageHandler =>
      log.debug(s"message handler stopped, stopping tcp handler for $remote")
      context unwatch messageHandler
      context stop self
  }

  def handleOutMessage: Receive = {
    case message: Message =>
      connection ! TcpHandler.envelope(message)
  }
}
