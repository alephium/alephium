package org.alephium.protocol.message

import scala.language.existentials

import akka.util.ByteString

import org.alephium.crypto.{ED25519, ED25519PublicKey, ED25519Signature}
import org.alephium.protocol.config.{DiscoveryConfig, GroupConfig}
import org.alephium.protocol.model._
import org.alephium.serde._
import org.alephium.util.AVector

final case class DiscoveryMessage(header: DiscoveryMessage.Header,
                                  payload: DiscoveryMessage.Payload)

object DiscoveryMessage {
  val version: Int = 0

  def from(myCliqueId: CliqueId, payload: Payload)(
      implicit config: DiscoveryConfig): DiscoveryMessage = {
    val header = Header(version, config.discoveryPublicKey, myCliqueId)
    DiscoveryMessage(header, payload)
  }

  final case class Header(version: Int, publicKey: ED25519PublicKey, cliqueId: CliqueId)
  object Header {
    private val serde = Serde.tuple3[Int, ED25519PublicKey, CliqueId]

    def serialize(header: Header): ByteString =
      serde.serialize((header.version, header.publicKey, header.cliqueId))

    def _deserialize(myCliqueId: CliqueId, input: ByteString)(
        implicit config: DiscoveryConfig): SerdeResult[(Header, ByteString)] = {
      serde._deserialize(input).flatMap {
        case ((_version, publicKey, cliqueId), rest) =>
          if (_version == version) {
            if (publicKey != config.discoveryPublicKey && cliqueId != myCliqueId) {
              Right((Header(_version, publicKey, cliqueId), rest))
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
    @SuppressWarnings(
      Array("org.wartremover.warts.Product",
            "org.wartremover.warts.Serializable",
            "org.wartremover.warts.JavaSerializable"))
    def serialize(payload: Payload): ByteString = {
      val (code: Code[_], data) = payload match {
        case x: Ping      => (Ping, Ping.serialize(x))
        case x: Pong      => (Pong, Pong.serialize(x))
        case x: FindNode  => (FindNode, FindNode.serialize(x))
        case x: Neighbors => (Neighbors, Neighbors.serialize(x))
      }
      intSerde.serialize(Code.toInt(code)) ++ data
    }

    def deserialize(input: ByteString)(implicit discoveryConfig: DiscoveryConfig,
                                       groupConfig: GroupConfig): SerdeResult[Payload] = {
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

  final case class Ping(cliqueInfoOpt: Option[InterCliqueInfo]) extends Payload
  object Ping extends Code[Ping] {
    private val serde: Serde[Option[InterCliqueInfo]] =
      optionSerde[InterCliqueInfo](InterCliqueInfo._serde)

    def serialize(ping: Ping): ByteString = serde.serialize(ping.cliqueInfoOpt)

    def deserialize(input: ByteString)(implicit discoveryConfig: DiscoveryConfig,
                                       groupConfig: GroupConfig): SerdeResult[Ping] = {
      serde
        .deserialize(input)
        .flatMap {
          case Some(info) =>
            InterCliqueInfo.validate(info) match {
              case Right(_)       => Right(Some(info))
              case Left(errorMsg) => Left(SerdeError.validation(errorMsg))
            }
          case None => Right(None)
        }
        .map(Ping.apply)
    }
  }

  final case class Pong(cliqueInfo: InterCliqueInfo) extends Payload
  object Pong extends Code[Pong] {
    def serialize(pong: Pong): ByteString =
      InterCliqueInfo.serialize(pong.cliqueInfo)

    def deserialize(input: ByteString)(implicit discoveryConfig: DiscoveryConfig,
                                       groupConfig: GroupConfig): SerdeResult[Pong] = {
      InterCliqueInfo.deserialize(input).map(Pong.apply)
    }
  }

  final case class FindNode(targetId: CliqueId) extends Payload
  object FindNode extends Code[FindNode] {
    private val serde = Serde.tuple1[CliqueId]

    def serialize(data: FindNode): ByteString =
      serde.serialize(data.targetId)

    def deserialize(input: ByteString)(implicit discoveryConfig: DiscoveryConfig,
                                       groupConfig: GroupConfig): SerdeResult[FindNode] =
      serde.deserialize(input).map(FindNode(_))
  }

  final case class Neighbors(peers: AVector[InterCliqueInfo]) extends Payload
  object Neighbors extends Code[Neighbors] {
    private val serializer = avectorSerializer[InterCliqueInfo]

    def serialize(data: Neighbors): ByteString = serializer.serialize(data.peers)

    private implicit val infoDeserializer = InterCliqueInfo._serde
    private val deserializer              = avectorDeserializer[InterCliqueInfo]
    def deserialize(input: ByteString)(implicit discoveryConfig: DiscoveryConfig,
                                       groupConfig: GroupConfig): SerdeResult[Neighbors] = {
      deserializer.deserialize(input).flatMap { peers =>
        peers.foreachE(InterCliqueInfo.validate) match {
          case Right(_)    => Right(Neighbors(peers))
          case Left(error) => Left(SerdeError.validation(error))
        }
      }
    }
  }

  sealed trait Code[T] {
    def serialize(t: T): ByteString
    def deserialize(input: ByteString)(implicit discoveryConfig: DiscoveryConfig,
                                       groupConfig: GroupConfig): SerdeResult[T]
  }
  object Code {
    val values: AVector[Code[_]] = AVector(Ping, Pong, FindNode, Neighbors)

    val toInt: Map[Code[_], Int] = values.toIterable.zipWithIndex.toMap
    def fromInt(code: Int): Option[Code[_]] = {
      if (code >= 0 && code < values.length) Some(values(code)) else None
    }
  }

  val deserializerCode: Deserializer[Code[_]] =
    intSerde.validateGet(Code.fromInt, c => s"Invalid message code '$c'")

  def serialize(message: DiscoveryMessage)(implicit config: DiscoveryConfig): ByteString = {
    val headerBytes  = Header.serialize(message.header)
    val payloadBytes = Payload.serialize(message.payload)
    val signature    = ED25519.sign(payloadBytes, config.discoveryPrivateKey)
    headerBytes ++ signature.bytes ++ payloadBytes
  }

  def deserialize(myCliqueId: CliqueId, input: ByteString)(
      implicit discoveryConfig: DiscoveryConfig,
      groupConfig: GroupConfig): SerdeResult[DiscoveryMessage] = {
    for {
      headerPair <- Header._deserialize(myCliqueId, input)
      header = headerPair._1
      rest1  = headerPair._2
      signaturePair <- serdeImpl[ED25519Signature]._deserialize(rest1)
      signature = signaturePair._1
      rest2     = signaturePair._2
      payload <- Payload.deserialize(rest2).flatMap { payload =>
        if (ED25519.verify(rest2, signature, header.publicKey)) Right(payload)
        else Left(SerdeError.validation(s"Invalid signature"))
      }
    } yield DiscoveryMessage(header, payload)
  }
}
