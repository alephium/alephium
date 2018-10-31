package org.alephium.protocol.message

import java.net.InetSocketAddress

import akka.util.ByteString
import org.alephium.protocol.config.DiscoveryConfig
import org.alephium.serde.{Deserializer, RandomBytes, Serde, ValidationException}
import org.alephium.protocol.model.{peerIdLength, GroupIndex, PeerId, PeerInfo}
import org.alephium.util.AVector

import scala.util.{Failure, Success, Try}

sealed trait DiscoveryMessage {
  def callId: DiscoveryMessage.CallId
}

object DiscoveryMessage {
  val version: Int = 0

  /** The 160bits (20bytes) identifier of a Remote Procedure Call **/
  class CallId private[CallId] (val bytes: ByteString) extends RandomBytes
  object CallId extends RandomBytes.Companion[CallId](new CallId(_), _.bytes) {
    override def length: Int = peerIdLength
  }

  trait Request extends DiscoveryMessage {
    def sourceId: PeerId
  }
  trait Response extends DiscoveryMessage

  case class FindNode(callId: CallId, sourceId: PeerId, targetId: PeerId) extends Request
  object FindNode extends Code[FindNode] {
    private val serde = Serde.tuple3[CallId, PeerId, PeerId]

    def serialize(data: FindNode): ByteString =
      serde.serialize((data.callId, data.sourceId, data.targetId))

    def deserialize(input: ByteString)(implicit config: DiscoveryConfig): Try[FindNode] =
      serde.deserialize(input).flatMap {
        case (callId, sourceId, targetId) =>
          if (sourceId != config.peerId) {
            Success(FindNode(callId, sourceId, targetId))
          } else {
            Failure(ValidationException(s"Invalide peerId $sourceId"))
          }
      }
  }

  case class Ping(callId: CallId, source: PeerInfo) extends Request {
    def sourceId: PeerId = source.id
  }
  object Ping extends Code[Ping] {
    private val serde = Serde.tuple3[CallId, PeerId, InetSocketAddress]

    def serialize(ping: Ping): ByteString =
      serde.serialize((ping.callId, ping.source.id, ping.source.socketAddress))

    def deserialize(input: ByteString)(implicit config: DiscoveryConfig): Try[Ping] = {
      serde.deserialize(input).flatMap {
        case (callId, sourceId, sourceAdress) =>
          if (sourceId != config.peerId) {
            Success(Ping(callId, PeerInfo(sourceId, sourceAdress)))
          } else {
            Failure(ValidationException(s"Invalid peerId $sourceId"))
          }
      }
    }
  }

  // Events
  case class Neighbors(callId: CallId, peers: AVector[AVector[PeerInfo]]) extends Response
  object Neighbors extends Code[Neighbors] {
    private val serde = Serde.tuple2[CallId, AVector[AVector[PeerInfo]]]

    def serialize(data: Neighbors): ByteString = serde.serialize((data.callId, data.peers))

    def deserialize(input: ByteString)(implicit config: DiscoveryConfig): Try[Neighbors] = {
      serde.deserialize(input).flatMap {
        case (callId, peers) =>
          if (peers.length == config.groups) {
            val ok = peers.forallWithIndex { (peers, idx) =>
              peers.forall(info =>
                info.id != config.peerId && info.id.groupIndex == GroupIndex.unsafe(idx))
            }
            if (ok) Success(Neighbors(callId, peers))
            else Failure(ValidationException(s"PeerInfos are invalid"))
          } else {
            Failure(
              ValidationException(s"Got ${peers.length} groups, expect ${config.groups} groups"))
          }
      }
    }
  }

  case class Pong(callId: CallId) extends Response
  object Pong extends Code[Pong] {
    private val serde: Serde[Pong] = Serde.forProduct1(Pong(_), _.callId)

    def serialize(pong: Pong): ByteString =
      serde.serialize(pong)

    def deserialize(input: ByteString)(implicit config: DiscoveryConfig): Try[Pong] =
      serde.deserialize(input)
  }

  sealed trait Code[T] {
    def serialize(t: T): ByteString
    def deserialize(input: ByteString)(implicit config: DiscoveryConfig): Try[T]
  }
  object Code {
    val values: AVector[Code[_]] = AVector(Ping, Pong, FindNode, Neighbors)

    val toInt: Map[Code[_], Int]   = values.toIterable.zipWithIndex.toMap
    val fromInt: Map[Int, Code[_]] = toInt.map(_.swap)
  }

  val deserializerCode: Deserializer[Code[_]] =
    Serde[Int].validateGet(Code.fromInt.get, c => s"Invalid message code '$c'")

  val deserializerVersion: Deserializer[Int] =
    Serde[Int]
      .validate(_ == version, v => s"Incompatible protocol version $v (expecting $version)")

  def serialize(message: DiscoveryMessage): ByteString = {
    val (code, data) = message match {
      case x: Ping      => (Ping, Ping.serialize(x))
      case x: Pong      => (Pong, Pong.serialize(x))
      case x: FindNode  => (FindNode, FindNode.serialize(x))
      case x: Neighbors => (Neighbors, Neighbors.serialize(x))
    }
    Serde[Int].serialize(version) ++ Serde[Int].serialize(Code.toInt(code)) ++ data
  }

  def deserialize(input: ByteString)(implicit config: DiscoveryConfig): Try[DiscoveryMessage] = {
    for {
      (_, rest1)   <- deserializerVersion._deserialize(input)
      (cmd, rest2) <- deserializerCode._deserialize(rest1)
      result <- cmd match {
        case Ping      => Ping.deserialize(rest2)
        case Pong      => Pong.deserialize(rest2)
        case FindNode  => FindNode.deserialize(rest2)
        case Neighbors => Neighbors.deserialize(rest2)
      }
    } yield result
  }
}
