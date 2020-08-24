package org.alephium.flow.network.broker

import scala.annotation.tailrec
import scala.collection.mutable

import akka.actor.Props
import akka.io.Tcp
import akka.util.ByteString

import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.message.{Message, Payload}
import org.alephium.serde.{SerdeError, SerdeResult}
import org.alephium.util.{ActorRefT, BaseActor}

object BrokerConnectionHandler {
  def clique(connection: ActorRefT[Tcp.Command])(implicit groupConfig: GroupConfig): Props =
    Props(new CliqueConnectionHander(connection))

  final case class Ack(id: Long) extends Tcp.Event

  sealed trait Command
  case object CloseConnection                extends Command
  final case class Send(message: ByteString) extends Command

  def tryDeserializePayload(data: ByteString)(
      implicit config: GroupConfig): SerdeResult[Option[(Payload, ByteString)]] = {
    Message._deserialize(data) match {
      case Right((message, newRest))          => Right(Some(message.payload -> newRest))
      case Left(_: SerdeError.NotEnoughBytes) => Right(None)
      case Left(e)                            => Left(e)
    }
  }

  class CliqueConnectionHander(val connection: ActorRefT[Tcp.Command])(
      implicit val groupConfig: GroupConfig)
      extends BrokerConnectionHandler[Payload] {

    val brokerHandler: ActorRefT[BrokerHandler.Command] = context.parent

    override def tryDeserialize(data: ByteString): SerdeResult[Option[(Payload, ByteString)]] = {
      tryDeserializePayload(data)
    }

    override def handleNewMessage(payload: Payload): Unit = {
      brokerHandler ! BrokerHandler.Received(payload)
    }
  }
}

trait BrokerConnectionHandler[T] extends BaseActor {
  import BrokerConnectionHandler._

  def connection: ActorRefT[Tcp.Command]

  override def preStart(): Unit = {
    context watch connection.ref
    connection ! Tcp.Register(self, keepOpenOnPeerClosed = false, useResumeWriting = true)
    connection ! Tcp.ResumeReading
  }

  def receive: Receive = communicating

  def communicating: Receive = reading orElse writing orElse close

  def bufferedCommunicating: Receive = reading orElse bufferedWriting orElse close

  def reading: Receive = {
    case Tcp.Received(data) =>
      bufferInMessage(data)
      processInMessageBuffer()
      connection ! Tcp.ResumeReading
  }

  def writing: Receive = {
    case Send(data) => write(data)
    case Ack(ack)   => acknowledge(ack) // TODO: add test and remove acknowledge
    case Tcp.CommandFailed(Tcp.Write(data, Ack(ack))) =>
      log.debug(s"Writing failed $ack")
      connection ! Tcp.ResumeWriting
      bufferOutMessage(ack, data)
      context become bufferedCommunicating
  }

  def bufferedWriting: Receive = {
    case Tcp.WritingResumed => writeFirst()
    case Send(data)         => bufferOutMessage(data)
    case Ack(ack) =>
      acknowledge(ack)
      if (outMessageBuffer.nonEmpty) {
        writeFirst()
      } else {
        context become communicating
      }
    case Tcp.CommandFailed(Tcp.Write(data, Ack(ack))) =>
      connection ! Tcp.ResumeWriting
      bufferOutMessage(ack, data)
  }

  def close: Receive = {
    case _: Tcp.ConnectionClosed =>
      log.debug(s"Peer connection closed")
      context stop self
    case CloseConnection =>
      if (outMessageBuffer.nonEmpty) {
        log.debug("Clear the buffer before closing the connection")
        writeAll()
        context become closing
      } else {
        log.debug("Close the connection")
        connection ! Tcp.Close
      }
  }

  def closing: Receive = {
    case Tcp.CommandFailed(_: Tcp.Write) =>
      connection ! Tcp.ResumeWriting
      context.become({
        case Tcp.WritingResumed =>
          writeAll()
          context.unbecome()
        case Ack(ack) => acknowledge(ack)
      }, discardOld = false)
    case Ack(ack) =>
      acknowledge(ack)
      if (outMessageBuffer.isEmpty) {
        connection ! Tcp.Close
      }
    case _: Tcp.ConnectionClosed =>
      log.debug(s"Peer connection closed")
      context stop self
    case other: ByteString =>
      log.debug(s"Got $other in closing phase")
  }

  final var outMessageCount  = 0L
  final val outMessageBuffer = mutable.TreeMap.empty[Long, ByteString]
  final var inMessageBuffer  = ByteString.empty

  def bufferInMessage(data: ByteString): Unit = {
    inMessageBuffer ++= data
  }

  def tryDeserialize(data: ByteString): SerdeResult[Option[(T, ByteString)]]
  def handleNewMessage(message: T): Unit

  @tailrec
  final def processInMessageBuffer(): Unit = {
    tryDeserialize(inMessageBuffer) match {
      case Right(Some((message, rest))) =>
        inMessageBuffer = rest
        handleNewMessage(message)
        processInMessageBuffer()
      case Right(None) => ()
      case Left(error) =>
        log.debug(s"Message deserialization error: $error")
        stopMaliciousPeer()
    }
  }

  def bufferOutMessage(id: Long, data: ByteString): Unit = {
    outMessageBuffer += id -> data
  }

  def bufferOutMessage(data: ByteString): Unit = {
    outMessageCount += 1
    outMessageBuffer += outMessageCount -> data
  }

  def acknowledge(id: Long): Unit = {
    outMessageBuffer -= id
  }

  def write(data: ByteString): Unit = {
    outMessageCount += 1
    connection ! Tcp.Write(data, Ack(outMessageCount))
  }

  def writeFirst(): Unit = {
    assume(outMessageBuffer.nonEmpty)
    outMessageBuffer.headOption.foreach {
      case (id, message) =>
        outMessageBuffer -= id
        connection ! Tcp.Write(message, Ack(id))
    }
  }

  def writeAll(): Unit = {
    for ((id, data) <- outMessageBuffer) {
      connection ! Tcp.Write(data, Ack(id))
    }
  }

  def stopMaliciousPeer(): Unit = {
    // TODO: block the malicious peer
    connection ! Tcp.Close
  }
}
