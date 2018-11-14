package org.alephium.protocol.message

import java.net.InetSocketAddress

import akka.util.ByteString
import org.alephium.crypto.{ED25519, ED25519PublicKey, ED25519Signature}
import org.alephium.protocol.config.DiscoveryConfig
import org.alephium.serde.{Deserializer, Serde, ValidationException, WrongFormatException}
import org.alephium.protocol.model.{GroupIndex, PeerId, PeerInfo}
import org.alephium.util.AVector

import scala.language.existentials
import scala.util.{Failure, Success, Try}

case class DiscoveryMessage(header: DiscoveryMessage.Header, payload: DiscoveryMessage.Payload)

object DiscoveryMessage {
  val version: Int = 0

  def from(payload: Payload)(implicit config: DiscoveryConfig): DiscoveryMessage = {
    val header = Header(version, config.discoveryPublicKey)
    DiscoveryMessage(header, payload)
  }

  case class Header(version: Int, publicKey: ED25519PublicKey)
  object Header {
    private val serde = Serde.tuple2[Int, ED25519PublicKey]

    def serialize(header: Header): ByteString = serde.serialize((header.version, header.publicKey))

    def _deserialize(input: ByteString)(
        implicit config: DiscoveryConfig): Try[(Header, ByteString)] = {
      serde._deserialize(input).flatMap {
        case ((_version, publicKey), rest) =>
          if (_version == version) {
            if (publicKey != config.discoveryPublicKey) {
              Success((Header(_version, publicKey), rest))
            } else {
              Failure(ValidationException(s"Peer's public key is the same as ours"))
            }
          } else {
            Failure(ValidationException(s"Invalid version: got ${_version}, expect: $version"))
          }
      }
    }
  }

  trait Payload
  object Payload {
    def serialize(payload: Payload): ByteString = {
      val (code: Code[_], data) = payload match {
        case x: Ping      => (Ping, Ping.serialize(x))
        case x: Pong      => (Pong, Pong.serialize(x))
        case x: FindNode  => (FindNode, FindNode.serialize(x))
        case x: Neighbors => (Neighbors, Neighbors.serialize(x))
      }
      Serde[Int].serialize(Code.toInt(code)) ++ data
    }

    def deserialize(input: ByteString)(implicit config: DiscoveryConfig): Try[Payload] = {
      for {
        (cmd, rest) <- deserializerCode._deserialize(input)
        result <- cmd match {
          case Ping      => Ping.deserialize(rest)
          case Pong      => Pong.deserialize(rest)
          case FindNode  => FindNode.deserialize(rest)
          case Neighbors => Neighbors.deserialize(rest)
        }
      } yield result
    }
  }

  sealed trait Request  extends Payload
  sealed trait Response extends Payload

  case class Ping(sourceAddress: InetSocketAddress) extends Request
  object Ping extends Code[Ping] {
    private val serde = Serde.tuple1[InetSocketAddress]

    def serialize(ping: Ping): ByteString =
      serde.serialize(ping.sourceAddress)

    def deserialize(input: ByteString)(implicit config: DiscoveryConfig): Try[Ping] = {
      serde.deserialize(input).map(Ping(_))
    }
  }

  case class FindNode(targetId: PeerId) extends Request
  object FindNode extends Code[FindNode] {
    private val serde = Serde.tuple1[PeerId]

    def serialize(data: FindNode): ByteString =
      serde.serialize(data.targetId)

    def deserialize(input: ByteString)(implicit config: DiscoveryConfig): Try[FindNode] =
      serde.deserialize(input).map(FindNode(_))
  }

  case class Pong() extends Response
  object Pong extends Code[Pong] {
    def serialize(pong: Pong): ByteString = ByteString.empty

    def deserialize(input: ByteString)(implicit config: DiscoveryConfig): Try[Pong] = {
      if (input.isEmpty) Success(Pong())
      else Failure(WrongFormatException.redundant(0, input.length))
    }
  }

  case class Neighbors(peers: AVector[AVector[PeerInfo]]) extends Response
  object Neighbors extends Code[Neighbors] {
    private val serde = Serde.tuple1[AVector[AVector[PeerInfo]]]

    def serialize(data: Neighbors): ByteString = serde.serialize(data.peers)

    def deserialize(input: ByteString)(implicit config: DiscoveryConfig): Try[Neighbors] = {
      serde.deserialize(input).flatMap { peers =>
        if (peers.length == config.groups) {
          val ok = peers.forallWithIndex { (peers, idx) =>
            peers.forall(info =>
              info.id != config.peerId && info.id.groupIndex == GroupIndex.unsafe(idx))
          }
          if (ok) Success(Neighbors(peers))
          else Failure(ValidationException(s"PeerInfos are invalid"))
        } else {
          Failure(
            ValidationException(s"Got ${peers.length} groups, expect ${config.groups} groups"))
        }
      }
    }
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

  def serialize(message: DiscoveryMessage)(implicit config: DiscoveryConfig): ByteString = {
    val headerBytes  = Header.serialize(message.header)
    val payloadBytes = Payload.serialize(message.payload)
    val signature    = ED25519.sign(payloadBytes, config.discoveryPrivateKey)
    headerBytes ++ signature.bytes ++ payloadBytes
  }

  def deserialize(input: ByteString)(implicit config: DiscoveryConfig): Try[DiscoveryMessage] = {
    for {
      (header, rest1)    <- Header._deserialize(input)
      (signature, rest2) <- Serde[ED25519Signature]._deserialize(rest1)
      payload <- Payload.deserialize(rest2).flatMap { payload =>
        if (ED25519.verify(rest2, signature, header.publicKey)) Success(payload)
        else Failure(ValidationException(s"Invalid signature"))
      }
    } yield DiscoveryMessage(header, payload)
  }
}
