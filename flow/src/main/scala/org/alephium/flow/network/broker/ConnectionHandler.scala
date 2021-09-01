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

import akka.actor.{Props, Terminated}
import akka.io.Tcp
import akka.util.ByteString
import io.prometheus.client.Counter

import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.config.{GroupConfig, NetworkConfig}
import org.alephium.protocol.message.{Message, Payload}
import org.alephium.serde.{SerdeError, SerdeResult, Staging}
import org.alephium.util.{ActorRefT, BaseActor, EventStream}

object ConnectionHandler {
  def clique(
      remoteAddress: InetSocketAddress,
      connection: ActorRefT[Tcp.Command],
      brokerHandler: ActorRefT[BrokerHandler.Command]
  )(implicit groupConfig: GroupConfig, networkSetting: NetworkSetting): Props =
    Props(new CliqueConnectionHandler(remoteAddress, connection, brokerHandler))

  final case class Ack(id: Long) extends Tcp.Event

  sealed trait Command
  case object CloseConnection                extends Command
  final case class Send(message: ByteString) extends Command

  def tryDeserializePayload(data: ByteString)(implicit
      groupConfig: GroupConfig,
      networkConfig: NetworkConfig
  ): SerdeResult[Option[Staging[Payload]]] = {
    Message._deserialize(data) match {
      case Right(Staging(message, newRest))   => Right(Some(Staging(message.payload, newRest)))
      case Left(_: SerdeError.NotEnoughBytes) => Right(None)
      case Left(e)                            => Left(e)
    }
  }

  class CliqueConnectionHandler(
      val remoteAddress: InetSocketAddress,
      val connection: ActorRefT[Tcp.Command],
      val brokerHandler: ActorRefT[BrokerHandler.Command]
  )(implicit val groupConfig: GroupConfig, val networkSetting: NetworkSetting)
      extends ConnectionHandler[Payload] {
    override def tryDeserialize(data: ByteString): SerdeResult[Option[Staging[Payload]]] = {
      tryDeserializePayload(data)
    }

    override def handleNewMessage(payload: Payload): Unit = {
      payload.measure()
      brokerHandler ! BrokerHandler.Received(payload)
    }
  }

  val uploadBytesTotal: Counter = Counter
    .build(
      "alephium_upload_bytes_total",
      "Total upload bytes"
    )
    .labelNames("remote_address")
    .register()

  val downloadBytesTotal: Counter = Counter
    .build(
      "alephium_download_bytes_total",
      "Total upload bytes"
    )
    .labelNames("remote_address")
    .register()
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

  def bufferedCommunicating: Receive = reading orElse bufferedWriting orElse closed

  private val downloadBytesTotalLabeled =
    downloadBytesTotal.labels(remoteAddress.getAddress.getHostAddress)
  def reading: Receive = { case Tcp.Received(data) =>
    downloadBytesTotalLabeled.inc(data.length.toDouble)
    bufferInMessage(data)
    processInMessageBuffer()
    connection ! Tcp.ResumeReading
  }

  def writing: Receive = {
    case Send(data) => send(data)

    case Ack(_) => () // all are good

    case Tcp.CommandFailed(Tcp.Write(data, Ack(id))) =>
      log.debug(s"Failed in writing ${data.length} bytes with ack $id to $remoteAddress")
      connection ! Tcp.ResumeWriting
      buffer(data, id)
      context become bufferedCommunicating

    case Tcp.WritingResumed => () // might due to duplicated resume in buffer mode

    case Tcp.PeerClosed | CloseConnection =>
      if (outMessageBuffer.isEmpty) {
        connection ! Tcp.Close
      } else {
        context.become(closing orElse closed)
      }
  }

  def bufferedWriting: Receive = {
    case Send(data) => buffer(data)

    case Tcp.WritingResumed => writeFirst()

    case Tcp.CommandFailed(Tcp.Write(data, Ack(id))) =>
      log.debug(s"Failed in writing ${data.length} bytes with ack $id to $remoteAddress")
      buffer(data, id)
      connection ! Tcp.ResumeWriting

    case Tcp.CommandFailed(Tcp.ResumeWriting) => ()

    case Ack(id) =>
      acknowledge(id)
      if (outMessageBuffer.nonEmpty) {
        writeFirst()
      } else {
        context.become(communicating)
      }

    case Tcp.PeerClosed | CloseConnection =>
      if (outMessageBuffer.isEmpty) {
        connection ! Tcp.Close
      } else {
        writeAll()
        context.become(closing orElse closed)
      }
  }

  def closed: Receive = { case _: Tcp.ConnectionClosed | Terminated(_) =>
    log.debug(s"Peer connection closed: [$remoteAddress]")
    context stop self
  }

  def closing: Receive = {
    case Tcp.CommandFailed(_: Tcp.Write) =>
      connection ! Tcp.ResumeWriting
      context.become(
        {
          case Tcp.WritingResumed =>
            writeAll()
            context.unbecome()
          case Ack(ack) => acknowledge(ack)
        },
        discardOld = false
      )

    case Ack(ack) =>
      acknowledge(ack)
      if (outMessageBuffer.isEmpty) {
        connection ! Tcp.Close
      }
  }

  final private[broker] var outMessageCount         = 0L
  final private[broker] var outMessageBuffer        = mutable.TreeMap.empty[Long, ByteString]
  final private[broker] var outMessageBufferedBytes = 0L

  final val maxBufferCapacity = networkSetting.connectionBufferCapacityInByte

  protected def send(data: ByteString): Unit = {
    outMessageCount += 1
    sendData(data, outMessageCount)
  }

  protected def buffer(data: ByteString): Unit = {
    outMessageCount += 1
    buffer(data, outMessageCount)
  }

  protected def buffer(data: ByteString, id: Long): Unit = {
    val oldSize = outMessageBuffer.size
    outMessageBuffer.addOne(id -> data)
    val newSize = outMessageBuffer.size
    if (newSize > oldSize) {
      outMessageBufferedBytes += data.length
      if (outMessageBufferedBytes > maxBufferCapacity) {
        log.warning(s"Drop connection to [$remoteAddress] (buffer overrun)")
        context.stop(self)
      }
    }
  }

  private def acknowledge(ack: Long): Unit = {
    outMessageBuffer.remove(ack).foreach { data =>
      outMessageBufferedBytes -= data.length
    }
  }

  private def writeFirst(): Unit = {
    outMessageBuffer.headOption.foreach { case (id, data) =>
      sendData(data, id)
    }
  }

  private def writeAll(): Unit = {
    for ((id, data) <- outMessageBuffer) {
      sendData(data, id)
    }
  }

  private val uploadBytesTotalLabeled =
    uploadBytesTotal.labels(remoteAddress.getAddress.getHostAddress)
  private def sendData(data: ByteString, ack: Long): Unit = {
    connection ! Tcp.Write(data, Ack(ack))
    uploadBytesTotalLabeled.inc(data.length.toDouble)
  }

  final private var inMessageBuffer = ByteString.empty

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
        handleInvalidMessage(MisbehaviorManager.SerdeError(remoteAddress))
    }
  }

  def handleInvalidMessage(message: MisbehaviorManager.SerdeError): Unit = {
    publishEvent(message)
  }
}
