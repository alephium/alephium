// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.flow.network.broker

import java.net.InetSocketAddress

import scala.annotation.tailrec
import scala.collection.mutable

import akka.actor.Props
import akka.io.Tcp
import akka.util.ByteString

import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.message.{Message, Payload}
import org.alephium.protocol.model.NetworkType
import org.alephium.serde.{SerdeError, SerdeResult}
import org.alephium.util.{ActorRefT, BaseActor}

object ConnectionHandler {
  def clique(remoteAddress: InetSocketAddress,
             connection: ActorRefT[Tcp.Command],
             brokerHandler: ActorRefT[BrokerHandler.Command])(
      implicit groupConfig: GroupConfig,
      networkSetting: NetworkSetting): Props =
    Props(new CliqueConnectionHandler(remoteAddress, connection, brokerHandler))

  final case class Ack(id: Long) extends Tcp.Event

  sealed trait Command
  case object CloseConnection                extends Command
  final case class Send(message: ByteString) extends Command

  def tryDeserializePayload(data: ByteString, networkType: NetworkType)(
      implicit config: GroupConfig): SerdeResult[Option[(Payload, ByteString)]] = {
    Message._deserialize(data, networkType) match {
      case Right((message, newRest))          => Right(Some(message.payload -> newRest))
      case Left(_: SerdeError.NotEnoughBytes) => Right(None)
      case Left(e)                            => Left(e)
    }
  }

  class CliqueConnectionHandler(val remoteAddress: InetSocketAddress,
                                val connection: ActorRefT[Tcp.Command],
                                val brokerHandler: ActorRefT[BrokerHandler.Command])(
      implicit val groupConfig: GroupConfig,
      networkSetting: NetworkSetting)
      extends ConnectionHandler[Payload] {
    override def tryDeserialize(data: ByteString): SerdeResult[Option[(Payload, ByteString)]] = {
      tryDeserializePayload(data, networkSetting.networkType)
    }

    override def handleNewMessage(payload: Payload): Unit = {
      brokerHandler ! BrokerHandler.Received(payload)
    }
  }
}

trait ConnectionHandler[T] extends BaseActor {
  import ConnectionHandler._

  def remoteAddress: InetSocketAddress

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
      log.debug(s"Received data ${data.length} bytes from $remoteAddress")
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
        handleInvalidMessage(BrokerManager.InvalidMessage(remoteAddress))
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

  def handleInvalidMessage(message: BrokerManager.InvalidMessage): Unit = {
    publishEvent(message)
  }
}
