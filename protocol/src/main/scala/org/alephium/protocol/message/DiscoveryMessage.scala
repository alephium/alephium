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
import scala.reflect.ClassTag

import akka.util.ByteString

import org.alephium.protocol._
import org.alephium.protocol.config.{GroupConfig, NetworkConfig}
import org.alephium.protocol.model._
import org.alephium.serde._
import org.alephium.util.AVector

final case class DiscoveryMessage(
    header: DiscoveryMessage.Header,
    payload: DiscoveryMessage.Payload
)

object DiscoveryMessage {
  final case class Id(value: Hash) extends AnyVal
  object Id {
    implicit val serde: Serde[Id] = Serde.forProduct1(Id.apply, _.value)

    def random(): Id = Id(Hash.random)
  }

  def from(payload: Payload): DiscoveryMessage = {
    val header = Header(DiscoveryVersion.currentDiscoveryVersion)
    DiscoveryMessage(header, payload)
  }

  final case class Header(version: DiscoveryVersion)
  object Header {
    implicit val serde: Serde[Header] = DiscoveryVersion.serde
      .validate(_version =>
        if (_version == DiscoveryVersion.currentDiscoveryVersion) {
          Right(())
        } else {
          Left(
            s"Invalid version: got ${_version}, expect: ${DiscoveryVersion.currentDiscoveryVersion}"
          )
        }
      )
      .xmap(apply, _.version)
  }

  trait Payload {
    def senderCliqueId: Option[CliqueId]
  }
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

  sealed trait Request extends Payload

  // the sender sends its brokerInfo if possible, otherwise sends its cliqueId
  final case class Ping(sessionId: Id, senderInfo: Option[BrokerInfo]) extends Request {
    def senderCliqueId: Option[CliqueId] = senderInfo.map(_.cliqueId)
  }
  object Ping extends Code[Ping] {
    private val senderInfoSerde: Serde[Option[BrokerInfo]] = optionSerde(BrokerInfo.serde)
    private val serde: Serde[Ping] = {
      Serde.forProduct2[Id, Option[BrokerInfo], Ping](
        Ping.apply,
        t => (t.sessionId, t.senderInfo)
      )(
        Id.serde,
        senderInfoSerde
      )
    }

    def serialize(ping: Ping): ByteString = serde.serialize(ping)

    def deserialize(
        input: ByteString
    )(implicit groupConfig: GroupConfig): SerdeResult[Ping] = {
      serde
        .deserialize(input)
        .flatMap {
          case ping @ Ping(_, Some(info)) =>
            BrokerInfo.validate(info) match {
              case Right(_)       => Right(ping)
              case Left(errorMsg) => Left(SerdeError.validation(errorMsg))
            }
          case ping => Right(ping)
        }
    }
  }

  final case class Pong(sessionId: Id, brokerInfo: BrokerInfo) extends Payload {
    def senderCliqueId: Option[CliqueId] = Some(brokerInfo.cliqueId)
  }
  object Pong extends Code[Pong] {
    private val serde: Serde[Pong] =
      Serde.forProduct2[Id, BrokerInfo, Pong](Pong.apply, t => (t.sessionId, t.brokerInfo))(
        Id.serde,
        BrokerInfo.serde
      )

    def serialize(pong: Pong): ByteString = serde.serialize(pong)

    def deserialize(
        input: ByteString
    )(implicit groupConfig: GroupConfig): SerdeResult[Pong] = {
      serde
        .deserialize(input)
        .flatMap { case pong @ Pong(_, info) =>
          BrokerInfo.validate(info) match {
            case Right(_)       => Right(pong)
            case Left(errorMsg) => Left(SerdeError.validation(errorMsg))
          }
        }
    }
  }

  final case class FindNode(targetId: CliqueId) extends Request {
    def senderCliqueId: Option[CliqueId] = None
  }
  object FindNode extends Code[FindNode] {
    private val serde: Serde[FindNode] = Serde.forProduct1(FindNode.apply, t => t.targetId)

    def serialize(data: FindNode): ByteString = serde.serialize(data)

    def deserialize(
        input: ByteString
    )(implicit groupConfig: GroupConfig): SerdeResult[FindNode] =
      serde.deserialize(input)
  }

  final case class Neighbors(peers: AVector[BrokerInfo]) extends Payload {
    def senderCliqueId: Option[CliqueId] = None
  }
  object Neighbors extends Code[Neighbors] {
    val serde: Serde[Neighbors] = {
      val brokersSerde: Serde[AVector[BrokerInfo]] =
        avectorSerde(implicitly[ClassTag[BrokerInfo]], BrokerInfo.serde)
      Serde.forProduct1[AVector[BrokerInfo], Neighbors](
        Neighbors.apply,
        t => (t.peers)
      )(brokersSerde)
    }

    def serialize(data: Neighbors): ByteString = serde.serialize(data)

    def deserialize(input: ByteString)(implicit
        groupConfig: GroupConfig
    ): SerdeResult[Neighbors] = {
      serde.deserialize(input).flatMap { case message @ Neighbors(peers) =>
        peers.foreachE(BrokerInfo.validate) match {
          case Right(_)    => Right(message)
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
      privateKey: PrivateKey
  )(implicit networkConfig: NetworkConfig): ByteString = {
    val magic   = networkConfig.magicBytes
    val header  = Header.serde.serialize(message.header)
    val payload = Payload.serialize(message.payload)
    val signature = message.payload.senderCliqueId match {
      case Some(_) => SignatureSchema.sign(header ++ payload, privateKey).bytes
      case None    => Signature.zero.bytes
    }
    val data     = signature ++ header ++ payload
    val checksum = MessageSerde.checksum(data)
    val length   = MessageSerde.length(data)

    magic ++ checksum ++ length ++ data
  }

  def deserialize(input: ByteString)(implicit
      groupConfig: GroupConfig,
      networkConfig: NetworkConfig
  ): SerdeResult[DiscoveryMessage] = {
    MessageSerde
      .unwrap(input)
      .flatMap { case (checksum, length, rest) =>
        for {
          messageRest   <- MessageSerde.extractMessageBytes(length, rest)
          _             <- MessageSerde.checkChecksum(checksum, messageRest.value)
          signaturePair <- _deserialize[Signature](messageRest.value)
          headerRest    <- _deserialize[Header](signaturePair.rest)
          payload       <- Payload.deserialize(headerRest.rest)
          _ <- verifyPayloadSignature(
            signaturePair.rest,
            signaturePair.value,
            payload.senderCliqueId.map(_.publicKey)
          )
        } yield {
          DiscoveryMessage(headerRest.value, payload)
        }
      }
  }

  private def verifyPayloadSignature(
      message: ByteString,
      signature: Signature,
      publicKeyOpt: Option[PublicKey]
  ) = {
    publicKeyOpt match {
      case Some(publicKey) =>
        if (SignatureSchema.verify(message, signature, publicKey)) {
          Right(())
        } else {
          Left(SerdeError.validation(s"Invalid signature"))
        }
      case None =>
        if (signature == Signature.zero) {
          Right(())
        } else {
          Left(SerdeError.validation(s"Expect signature zero"))
        }
    }
  }
}
