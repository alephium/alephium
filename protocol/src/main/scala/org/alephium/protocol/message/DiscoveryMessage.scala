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

package org.alephium.protocol.message

import scala.language.existentials

import akka.util.ByteString

import org.alephium.protocol.{PrivateKey, PublicKey, Signature, SignatureSchema}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.serde._
import org.alephium.util.AVector

final case class DiscoveryMessage(
    header: DiscoveryMessage.Header,
    payload: DiscoveryMessage.Payload
)

object DiscoveryMessage {
  val version: Int = 0

  def from(myCliqueId: CliqueId, payload: Payload): DiscoveryMessage = {
    val header = Header(version, myCliqueId)
    DiscoveryMessage(header, payload)
  }

  final case class Header(version: Int, cliqueId: CliqueId) {
    val publicKey: PublicKey = cliqueId.publicKey
  }
  object Header {
    private val serde = Serde.tuple2[Int, CliqueId]

    def serialize(header: Header): ByteString =
      serde.serialize((header.version, header.cliqueId))

    def _deserialize(input: ByteString): SerdeResult[Staging[Header]] = {
      serde._deserialize(input).flatMap { case Staging((_version, cliqueId), rest) =>
        if (_version == version) {
          Right(Staging(Header(_version, cliqueId), rest))
        } else {
          Left(SerdeError.validation(s"Invalid version: got ${_version}, expect: $version"))
        }
      }
    }
  }

  trait Payload
  object Payload {
    @SuppressWarnings(
      Array(
        "org.wartremover.warts.Product",
        "org.wartremover.warts.Serializable",
        "org.wartremover.warts.JavaSerializable"
      )
    )
    def serialize(payload: Payload): ByteString = {
      val (code: Code[_], data) = payload match {
        case x: Ping      => (Ping, Ping.serialize(x))
        case x: Pong      => (Pong, Pong.serialize(x))
        case x: FindNode  => (FindNode, FindNode.serialize(x))
        case x: Neighbors => (Neighbors, Neighbors.serialize(x))
      }
      intSerde.serialize(Code.toInt(code)) ++ data
    }

    def deserialize(
        input: ByteString
    )(implicit groupConfig: GroupConfig): SerdeResult[Payload] = {
      deserializerCode._deserialize(input).flatMap { case Staging(cmd, rest) =>
        cmd match {
          case Ping      => Ping.deserialize(rest)
          case Pong      => Pong.deserialize(rest)
          case FindNode  => FindNode.deserialize(rest)
          case Neighbors => Neighbors.deserialize(rest)
        }
      }
    }
  }

  final case class Ping(brokerInfoOpt: Option[BrokerInfo]) extends Payload
  object Ping extends Code[Ping] {
    private val serde: Serde[Option[BrokerInfo]] = optionSerde[BrokerInfo](BrokerInfo._serde)

    def serialize(ping: Ping): ByteString = serde.serialize(ping.brokerInfoOpt)

    def deserialize(
        input: ByteString
    )(implicit groupConfig: GroupConfig): SerdeResult[Ping] = {
      serde
        .deserialize(input)
        .flatMap {
          case Some(info) =>
            BrokerInfo.validate(info) match {
              case Right(_)       => Right(Some(info))
              case Left(errorMsg) => Left(SerdeError.validation(errorMsg))
            }
          case None => Right(None)
        }
        .map(Ping.apply)
    }
  }

  final case class Pong(brokerInfo: BrokerInfo) extends Payload
  object Pong extends Code[Pong] {
    def serialize(pong: Pong): ByteString = BrokerInfo.serialize(pong.brokerInfo)

    def deserialize(
        input: ByteString
    )(implicit groupConfig: GroupConfig): SerdeResult[Pong] = {
      BrokerInfo.deserialize(input).map(Pong.apply)
    }
  }

  final case class FindNode(targetId: CliqueId) extends Payload
  object FindNode extends Code[FindNode] {
    private val serde = Serde.tuple1[CliqueId]

    def serialize(data: FindNode): ByteString =
      serde.serialize(data.targetId)

    def deserialize(
        input: ByteString
    )(implicit groupConfig: GroupConfig): SerdeResult[FindNode] =
      serde.deserialize(input).map(FindNode(_))
  }

  final case class Neighbors(peers: AVector[BrokerInfo]) extends Payload
  object Neighbors extends Code[Neighbors] {
    private val serializer = avectorSerializer[BrokerInfo]

    def serialize(data: Neighbors): ByteString = serializer.serialize(data.peers)

    implicit private val infoDeserializer = BrokerInfo._serde
    private val deserializer              = avectorDeserializer[BrokerInfo]
    def deserialize(input: ByteString)(implicit
        groupConfig: GroupConfig
    ): SerdeResult[Neighbors] = {
      deserializer.deserialize(input).flatMap { peers =>
        peers.foreachE(BrokerInfo.validate) match {
          case Right(_)    => Right(Neighbors(peers))
          case Left(error) => Left(SerdeError.validation(error))
        }
      }
    }
  }

  sealed trait Code[T] {
    def serialize(t: T): ByteString
    def deserialize(
        input: ByteString
    )(implicit groupConfig: GroupConfig): SerdeResult[T]
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

  def serialize(
      message: DiscoveryMessage,
      networkType: NetworkType,
      privateKey: PrivateKey
  ): ByteString = {

    val magic     = networkType.magicBytes
    val header    = Header.serialize(message.header)
    val payload   = Payload.serialize(message.payload)
    val signature = SignatureSchema.sign(payload, privateKey).bytes
    val checksum  = MessageSerde.checksum(payload)
    val length    = MessageSerde.length(payload)

    magic ++ checksum ++ length ++ signature ++ header ++ payload
  }

  def deserialize(input: ByteString, networkType: NetworkType)(implicit
      groupConfig: GroupConfig
  ): SerdeResult[DiscoveryMessage] = {
    MessageSerde
      .unwrap(input, networkType)
      .flatMap { case (checksum, length, rest) =>
        for {
          signaturePair <- _deserialize[Signature](rest)
          headerRest    <- Header._deserialize(signaturePair.rest)
          payloadBytes  <- MessageSerde.extractPayloadBytes(length, headerRest.rest)
          _             <- MessageSerde.checkChecksum(checksum, payloadBytes.value)
          _ <- verifyPayloadSignature(
            payloadBytes.value,
            signaturePair.value,
            headerRest.value.publicKey
          )
          payload <- deserializeExactPayload(payloadBytes.value)
        } yield {
          DiscoveryMessage(headerRest.value, payload)
        }
      }
  }

  private def verifyPayloadSignature(
      payload: ByteString,
      signature: Signature,
      publicKey: PublicKey
  ) = {
    if (SignatureSchema.verify(payload, signature, publicKey)) {
      Right(())
    } else {
      Left(SerdeError.validation(s"Invalid signature"))
    }
  }

  private def deserializeExactPayload(
      payloadBytes: ByteString
  )(implicit config: GroupConfig) = {
    Payload.deserialize(payloadBytes).left.map {
      case _: SerdeError.NotEnoughBytes =>
        SerdeError.wrongFormat("Cannot extract a correct payload from the length field")
      case error => error
    }
  }
}
