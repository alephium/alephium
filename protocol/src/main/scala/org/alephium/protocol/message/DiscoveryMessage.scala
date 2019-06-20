package org.alephium.protocol.message

import akka.util.ByteString
import org.alephium.crypto.{ED25519, ED25519PublicKey, ED25519Signature}
import org.alephium.protocol.config.DiscoveryConfig
import org.alephium.serde._
import org.alephium.protocol.model._
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
        implicit config: DiscoveryConfig): SerdeResult[(Header, ByteString)] = {
      serde._deserialize(input).flatMap {
        case ((_version, publicKey), rest) =>
          if (_version == version) {
            if (publicKey != config.discoveryPublicKey) {
              Right((Header(_version, publicKey), rest))
            } else {
              Left(SerdeError.validation(s"Peer's public key is the same as ours"))
            }
          } else {
            Left(SerdeError.validation(s"Invalid version: got ${_version}, expect: $version"))
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

    def deserialize(input: ByteString)(implicit config: DiscoveryConfig): SerdeResult[Payload] = {
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

  case class Ping(cliqueInfo: CliqueInfo) extends Payload
  object Ping extends Code[Ping] {
    def serialize(ping: Ping): ByteString =
      implicitly[Serializer[CliqueInfo]].serialize(ping.cliqueInfo)

    def deserialize(input: ByteString)(implicit config: DiscoveryConfig): SerdeResult[Ping] = {
      val unsafe = implicitly[Serde[CliqueInfo.Unsafe]].deserialize(input)
      unsafe.flatMap(_.validate.left.map(SerdeError.validation)).map(Ping.apply)
    }
  }

  case class Pong(cliqueInfo: CliqueInfo) extends Payload
  object Pong extends Code[Pong] {
    def serialize(pong: Pong): ByteString =
      implicitly[Serializer[CliqueInfo]].serialize(pong.cliqueInfo)

    def deserialize(input: ByteString)(implicit config: DiscoveryConfig): SerdeResult[Pong] = {
      val unsafe = implicitly[Serde[CliqueInfo.Unsafe]].deserialize(input)
      unsafe.flatMap(_.validate.left.map(SerdeError.validation)).map(Pong.apply)
    }
  }

  case class FindNode(targetId: CliqueId) extends Payload
  object FindNode extends Code[FindNode] {
    private val serde = Serde.tuple1[CliqueId]

    def serialize(data: FindNode): ByteString =
      serde.serialize(data.targetId)

    def deserialize(input: ByteString)(implicit config: DiscoveryConfig): SerdeResult[FindNode] =
      serde.deserialize(input).map(FindNode(_))
  }

  case class Neighbors(peers: AVector[CliqueInfo]) extends Payload
  object Neighbors extends Code[Neighbors] {
    private val serializer = avectorSerializer[CliqueInfo]

    def serialize(data: Neighbors): ByteString = serializer.serialize(data.peers)

    private val deserializer = avectorDeserializer[CliqueInfo.Unsafe]
    def deserialize(input: ByteString)(implicit config: DiscoveryConfig): SerdeResult[Neighbors] = {
      deserializer.deserialize(input).flatMap { peers =>
        peers.traverse(_.validate) match {
          case Left(message) => Left(SerdeError.validation(message))
          case Right(infos)  => Right(Neighbors(infos))
        }
      }
    }
  }

  sealed trait Code[T] {
    def serialize(t: T): ByteString
    def deserialize(input: ByteString)(implicit config: DiscoveryConfig): SerdeResult[T]
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
      implicit config: DiscoveryConfig): SerdeResult[DiscoveryMessage] = {
    for {
      headerPair <- Header._deserialize(input)
      header = headerPair._1
      rest1  = headerPair._2
      signaturePair <- Serde[ED25519Signature]._deserialize(rest1)
      signature = signaturePair._1
      rest2     = signaturePair._2
      payload <- Payload.deserialize(rest2).flatMap { payload =>
        if (ED25519.verify(rest2, signature, header.publicKey)) Right(payload)
        else Left(SerdeError.validation(s"Invalid signature"))
      }
    } yield DiscoveryMessage(header, payload)
  }
}
