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

import akka.actor.{Props, Terminated}
import akka.io.Tcp
import akka.util.ByteString

import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.message.{Message, Payload}
import org.alephium.protocol.model.NetworkType
import org.alephium.serde.{SerdeError, SerdeResult, Staging}
import org.alephium.util.{ActorRefT, BaseActor, EventStream}

object ConnectionHandler {
  def clique(remoteAddress: InetSocketAddress,
             connection: ActorRefT[Tcp.Command],
             brokerHandler: ActorRefT[BrokerHandler.Command])(
      implicit groupConfig: GroupConfig,
      networkSetting: NetworkSetting): Props =
    Props(new CliqueConnectionHandler(remoteAddress, connection, brokerHandler))

  final case class Ack(id: Int) extends Tcp.Event

  sealed trait Command
  case object CloseConnection                extends Command
  final case class Send(message: ByteString) extends Command

  def tryDeserializePayload(data: ByteString, networkType: NetworkType)(
      implicit config: GroupConfig): SerdeResult[Option[Staging[Payload]]] = {
    Message._deserialize(data, networkType) match {
      case Right(Staging(message, newRest))   => Right(Some(Staging(message.payload, newRest)))
      case Left(_: SerdeError.NotEnoughBytes) => Right(None)
      case Left(e)                            => Left(e)
    }
  }

  class CliqueConnectionHandler(val remoteAddress: InetSocketAddress,
                                val connection: ActorRefT[Tcp.Command],
                                val brokerHandler: ActorRefT[BrokerHandler.Command])(
      implicit val groupConfig: GroupConfig,
      val networkSetting: NetworkSetting)
      extends ConnectionHandler[Payload] {
    override def tryDeserialize(data: ByteString): SerdeResult[Option[Staging[Payload]]] = {
      tryDeserializePayload(data, networkSetting.networkType)
    }

    override def handleNewMessage(payload: Payload): Unit = {
      brokerHandler ! BrokerHandler.Received(payload)
    }
  }
}

trait ConnectionHandler[T] extends BaseActor with EventStream.Publisher {
  import ConnectionHandler._

  def remoteAddress: InetSocketAddress

  def networkSetting: NetworkSetting

  def connection: ActorRefT[Tcp.Command]

  override def preStart(): Unit = {
    context watch connection.ref
    connection ! Tcp.Register(self, keepOpenOnPeerClosed = false, useResumeWriting = true)
    connection ! Tcp.ResumeReading
  }

  def receive: Receive = communicating

  def communicating: Receive = reading orElse writing orElse closed

  def bufferedCommunicating(ack: Int): Receive = reading orElse bufferedWriting(ack) orElse closed

  def reading: Receive = {
    case Tcp.Received(data) =>
      bufferInMessage(data)
      processInMessageBuffer()
      connection ! Tcp.ResumeReading
  }

  def writing: Receive = {
    case Send(data) =>
      connection ! buildTcpWrite(data)
      buffer(data)
    case Ack(ack) =>
      acknowledge(ack)
    case Tcp.CommandFailed(Tcp.Write(_, Ack(ack))) =>
      log.debug(s"Writing failed $ack")
      connection ! Tcp.ResumeWriting
      context become bufferedCommunicating(ack)
    case Tcp.PeerClosed | CloseConnection =>
      if (storage.isEmpty) connection ! Tcp.Close else context.become(closing orElse closed)
  }

  def bufferedWriting(nack: Int): Receive = {
    var toAck   = 10
    var toClose = false

    {
      case Send(data)             => buffer(data)
      case Tcp.WritingResumed     => writeFirst()
      case Ack(ack) if ack < nack => acknowledge(ack)
      case Ack(ack) =>
        acknowledge(ack)
        if (storage.nonEmpty) {
          if (toAck > 0) {
            // stay in ACK-based mode for a while
            writeFirst()
            toAck -= 1
          } else {
            // then return to NACK-based again
            writeAll()
            context.become(communicating)
          }
        } else if (toClose) {
          connection ! Tcp.Close
        } else {
          context.become(communicating)
        }
      case Tcp.PeerClosed | CloseConnection => toClose = true
    }
  }

  def closed: Receive = {
    case _: Tcp.ConnectionClosed | Terminated(_) =>
      log.debug(s"Peer connection closed: [$remoteAddress]")
      context stop self
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
      if (storage.isEmpty) {
        connection ! Tcp.Close
      }
  }

  override def postStop(): Unit = {
    log.debug(s"transferred $transferred bytes from/to [$remoteAddress]")
  }

  protected def buildTcpWrite(data: ByteString): Tcp.Write =
    Tcp.Write(data, Ack(currentOffset))

  private[broker] final var storageOffset = 0
  private[broker] final var storage       = Vector.empty[ByteString]
  private[broker] final var stored        = 0L
  private[broker] final var transferred   = 0L

  final val maxStored                 = networkSetting.connectionBufferCapacityInByte
  final val highWatermark             = maxStored * 5 / 10
  final val lowWatermark              = maxStored * 3 / 10
  private[broker] final var suspended = false

  private def currentOffset = storageOffset + storage.size

  protected def buffer(data: ByteString): Unit = {
    storage :+= data
    stored += data.size

    if (stored > maxStored) {
      log.warning(s"drop connection to [$remoteAddress] (buffer overrun)")
      context.stop(self)

    } else if (stored > highWatermark) {
      log.debug(s"suspending reading at $currentOffset")
      connection ! Tcp.SuspendReading
      suspended = true
    }
  }

  private def acknowledge(ack: Int): Unit = {
    require(ack == storageOffset, s"received ack $ack at $storageOffset")
    require(storage.nonEmpty, s"storage was empty at ack $ack")

    val size = storage(0).size
    stored -= size
    transferred += size

    storageOffset += 1
    storage = storage.drop(1)

    if (suspended && stored < lowWatermark) {
      log.debug("resuming reading")
      connection ! Tcp.ResumeReading
      suspended = false
    }
  }

  private def writeFirst(): Unit = {
    assume(storage.nonEmpty)
    connection ! Tcp.Write(storage(0), Ack(storageOffset))
  }

  private def writeAll(): Unit = {
    for ((data, i) <- storage.zipWithIndex) {
      connection ! Tcp.Write(data, Ack(storageOffset + i))
    }
  }

  private final var inMessageBuffer = ByteString.empty

  def bufferInMessage(data: ByteString): Unit = {
    inMessageBuffer ++= data
  }

  def tryDeserialize(data: ByteString): SerdeResult[Option[Staging[T]]]
  def handleNewMessage(message: T): Unit

  @tailrec
  final def processInMessageBuffer(): Unit = {
    tryDeserialize(inMessageBuffer) match {
      case Right(Some(Staging(message, rest))) =>
        inMessageBuffer = rest
        handleNewMessage(message)
        processInMessageBuffer()
      case Right(None) => ()
      case Left(error) =>
        log.debug(s"Message deserialization error: $error")
        handleInvalidMessage(MisbehaviorManager.InvalidMessage(remoteAddress))
    }
  }

  def handleInvalidMessage(message: MisbehaviorManager.InvalidMessage): Unit = {
    publishEvent(message)
  }
}
