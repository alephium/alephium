package org.alephium.protocol.message

import java.net.InetSocketAddress

import akka.util.ByteString
import org.alephium.crypto.{ED25519, ED25519PublicKey, ED25519Signature}
import org.alephium.protocol.config.DiscoveryConfig
import org.alephium.serde.{Deserializer, Serde, SerdeError, ValidationError, WrongFormatError}
import org.alephium.serde.{Deserializer, Serde, SerdeError, ValidationError, WrongFormatError}
import org.alephium.protocol.model.{GroupIndex, PeerId, PeerInfo}
import org.alephium.util.AVector

import scala.language.existentials

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
        implicit config: DiscoveryConfig): Either[SerdeError, (Header, ByteString)] = {
      serde._deserialize(input).flatMap {
        case ((_version, publicKey), rest) =>
          if (_version == version) {
            if (publicKey != config.discoveryPublicKey) {
              Right((Header(_version, publicKey), rest))
            } else {
              Left(ValidationError(s"Peer's public key is the same as ours"))
            }
          } else {
            Left(ValidationError(s"Invalid version: got ${_version}, expect: $version"))
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

    def deserialize(input: ByteString)(
        implicit config: DiscoveryConfig): Either[SerdeError, Payload] = {
      deserializerCode._deserialize(input).flatMap {
        case (cmd, rest) =>
          cmd match {
            case Ping      => Ping.deserialize(rest)
            case Pong      => Pong.deserialize(rest)
            case FindNode  => FindNode.deserialize(rest)
            case Neighbors => Neighbors.deserialize(rest)
          }
      }
    }
  }

  case class Ping(sourceAddress: InetSocketAddress) extends Payload
  object Ping extends Code[Ping] {
    private val serde = Serde.tuple1[InetSocketAddress]

    def serialize(ping: Ping): ByteString =
      serde.serialize(ping.sourceAddress)

    def deserialize(input: ByteString)(
        implicit config: DiscoveryConfig): Either[SerdeError, Ping] = {
      serde.deserialize(input).map(Ping(_))
    }
  }

  case class Pong() extends Payload
  object Pong extends Code[Pong] {
    def serialize(pong: Pong): ByteString = ByteString.empty

    def deserialize(input: ByteString)(
        implicit config: DiscoveryConfig): Either[SerdeError, Pong] = {
      if (input.isEmpty) Right(Pong())
      else Left(WrongFormatError.redundant(0, input.length))
    }
  }

  case class FindNode(targetId: PeerId) extends Payload
  object FindNode extends Code[FindNode] {
    private val serde = Serde.tuple1[PeerId]

    def serialize(data: FindNode): ByteString =
      serde.serialize(data.targetId)

    def deserialize(input: ByteString)(
        implicit config: DiscoveryConfig): Either[SerdeError, FindNode] =
      serde.deserialize(input).map(FindNode(_))
  }

  case class Neighbors(peers: AVector[AVector[PeerInfo]]) extends Payload
  object Neighbors extends Code[Neighbors] {
    private val serde = Serde.tuple1[AVector[AVector[PeerInfo]]]

    def serialize(data: Neighbors): ByteString = serde.serialize(data.peers)

    def deserialize(input: ByteString)(
        implicit config: DiscoveryConfig): Either[SerdeError, Neighbors] = {
      serde.deserialize(input).flatMap { peers =>
        if (peers.length == config.groups) {
          val ok = peers.forallWithIndex { (peers, idx) =>
            peers.forall(info =>
              info.id != config.nodeId && info.id.groupIndex == GroupIndex.unsafe(idx))
          }
          if (ok) Right(Neighbors(peers))
          else Left(ValidationError(s"PeerInfos are invalid"))
        } else {
          Left(ValidationError(s"Got ${peers.length} groups, expect ${config.groups} groups"))
        }
      }
    }
  }

  sealed trait Code[T] {
    def serialize(t: T): ByteString
    def deserialize(input: ByteString)(implicit config: DiscoveryConfig): Either[SerdeError, T]
  }
  object Code {
    val values: AVector[Code[_]] = AVector(Ping, Pong, FindNode, Neighbors)

    val toInt: Map[Code[_], Int] = values.toIterable.zipWithIndex.toMap
    def fromInt(code: Int): Option[Code[_]] = {
      if (code >= 0 && code < values.length) Some(values(code)) else None
    }
  }

  val deserializerCode: Deserializer[Code[_]] =
    Serde[Int].validateGet(Code.fromInt, c => s"Invalid message code '$c'")

  def serialize(message: DiscoveryMessage)(implicit config: DiscoveryConfig): ByteString = {
    val headerBytes  = Header.serialize(message.header)
    val payloadBytes = Payload.serialize(message.payload)
    val signature    = ED25519.sign(payloadBytes, config.discoveryPrivateKey)
    headerBytes ++ signature.bytes ++ payloadBytes
  }

  def deserialize(input: ByteString)(
      implicit config: DiscoveryConfig): Either[SerdeError, DiscoveryMessage] = {
    for {
      headerPair <- Header._deserialize(input)
      header = headerPair._1
      rest1  = headerPair._2
      signaturePair <- Serde[ED25519Signature]._deserialize(rest1)
      signature = signaturePair._1
      rest2     = signaturePair._2
      payload <- Payload.deserialize(rest2).flatMap { payload =>
        if (ED25519.verify(rest2, signature, header.publicKey)) Right(payload)
        else Left(ValidationError(s"Invalid signature"))
      }
    } yield DiscoveryMessage(header, payload)
  }
}
