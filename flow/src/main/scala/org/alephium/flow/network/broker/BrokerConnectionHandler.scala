package org.alephium.flow.network.broker

import scala.annotation.tailrec
import scala.collection.mutable

import akka.actor.Props
import akka.io.Tcp
import akka.util.ByteString

import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.message.Message
import org.alephium.serde.{SerdeError, SerdeResult}
import org.alephium.util.{ActorRefT, BaseActor}

object BrokerConnectionHandler {
  def props(connection: ActorRefT[Tcp.Command])(implicit groupConfig: GroupConfig): Props =
    Props(new Impl(connection))

  final case class Ack(id: Long) extends Tcp.Event

  sealed trait Command
  case object CloseConnection                extends Command
  final case class Send(message: ByteString) extends Command

  def tryDeserialize(data: ByteString)(
      implicit config: GroupConfig): SerdeResult[Option[(Message, ByteString)]] = {
    Message._deserialize(data) match {
      case Right((message, newRest))          => Right(Some(message -> newRest))
      case Left(_: SerdeError.NotEnoughBytes) => Right(None)
      case Left(e)                            => Left(e)
    }
  }

  class Impl(val connection: ActorRefT[Tcp.Command])(implicit val groupConfig: GroupConfig)
      extends BrokerConnectionHandler
}

trait BrokerConnectionHandler extends BaseActor {
  import Tcp._

  import BrokerConnectionHandler._

  implicit def groupConfig: GroupConfig

  def connection: ActorRefT[Tcp.Command]

  val brokerHandler = ActorRefT[BrokerHandler.Command](context.parent)

  override def preStart(): Unit = {
    context watch connection.ref
    connection ! Register(self, keepOpenOnPeerClosed = false, useResumeWriting = true)
    connection ! ResumeReading
  }

  def receive: Receive = communicating

  def communicating: Receive = reading orElse writing orElse close

  def bufferedCommunicating: Receive = reading orElse bufferedWriting orElse close

  def reading: Receive = {
    case Received(data) =>
      bufferInMessage(data)
      processInMessageBuffer()
      connection ! ResumeReading
  }

  def writing: Receive = {
    case Send(data) => write(data)
    case Ack(ack)   => acknowledge(ack) // TODO: add test and remove acknowledge
    case CommandFailed(Write(data, Ack(ack))) =>
      log.debug(s"Writing failed $ack")
      connection ! ResumeWriting
      bufferOutMessage(ack, data)
      context become bufferedCommunicating
  }

  def bufferedWriting: Receive = {
    case WritingResumed => writeFirst()
    case Send(data)     => bufferOutMessage(data)
    case Ack(ack) =>
      acknowledge(ack)
      if (outMessageBuffer.nonEmpty) {
        writeFirst()
      } else {
        context become communicating
      }
    case CommandFailed(Write(data, Ack(ack))) =>
      connection ! ResumeWriting
      bufferOutMessage(ack, data)
  }

  def close: Receive = {
    case _: ConnectionClosed =>
      log.debug(s"Peer connection closed")
      context stop self
    case CloseConnection =>
      writeAll()
      context become closing
  }

  def closing: Receive = {
    case CommandFailed(_: Write) =>
      connection ! ResumeWriting
      context.become({
        case WritingResumed =>
          writeAll()
          context.unbecome()
        case Ack(ack) => acknowledge(ack)
      }, discardOld = false)
    case Ack(ack) =>
      acknowledge(ack)
      if (outMessageBuffer.isEmpty) connection ! Close
    case other: ByteString =>
      log.debug(s"Got $other in closing phase")
  }

  final var outMessageCount  = 0L
  final val outMessageBuffer = mutable.TreeMap.empty[Long, ByteString]
  final var inMessageBuffer  = ByteString.empty

  def bufferInMessage(data: ByteString): Unit = {
    inMessageBuffer ++= data
  }

  @tailrec
  final def processInMessageBuffer(): Unit = {
    tryDeserialize(inMessageBuffer) match {
      case Right(Some((message, rest))) =>
        inMessageBuffer = rest
        brokerHandler ! BrokerHandler.Received(message.payload)
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
    connection ! Write(data, Ack(outMessageCount))
  }

  def writeFirst(): Unit = {
    assume(outMessageBuffer.nonEmpty)
    outMessageBuffer.headOption.foreach {
      case (id, message) =>
        outMessageBuffer -= id
        connection ! Write(message, Ack(id))
    }
  }

  def writeAll(): Unit = {
    for ((id, data) <- outMessageBuffer) {
      connection ! Write(data, Ack(id))
    }
    outMessageBuffer.clear()
  }

  def stopMaliciousPeer(): Unit = {
    // TODO: block the malicious peer
    connection ! Close
  }
}
