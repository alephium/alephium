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

import akka.util.ByteString
import io.prometheus.client.Counter

import org.alephium.protocol._
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.serde._
import org.alephium.util.{AVector, TimeStamp}

sealed trait Payload extends Product {
  val name = productPrefix

  def measure(): Unit
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
    val (code, data: ByteString) = payload match {
      case x: Hello        => (Hello, Hello.serialize(x))
      case x: Ping         => (Ping, Ping.serialize(x))
      case x: Pong         => (Pong, Pong.serialize(x))
      case x: SendBlocks   => (SendBlocks, SendBlocks.serialize(x))
      case x: GetBlocks    => (GetBlocks, GetBlocks.serialize(x))
      case x: SendHeaders  => (SendHeaders, SendHeaders.serialize(x))
      case x: GetHeaders   => (GetHeaders, GetHeaders.serialize(x))
      case x: SendTxs      => (SendTxs, SendTxs.serialize(x))
      case x: SyncRequest  => (SyncRequest, SyncRequest.serialize(x))
      case x: SyncResponse => (SyncResponse, SyncResponse.serialize(x))
    }
    intSerde.serialize(Code.toInt(code)) ++ data
  }

  val deserializerCode: Deserializer[Code] =
    intSerde.validateGet(Code.fromInt, c => s"Invalid code $c")

  def _deserialize(
      input: ByteString
  )(implicit config: GroupConfig): SerdeResult[Staging[Payload]] = {
    deserializerCode._deserialize(input).flatMap { case Staging(code, rest) =>
      code match {
        case Hello        => Hello._deserialize(rest)
        case Ping         => Ping._deserialize(rest)
        case Pong         => Pong._deserialize(rest)
        case SendBlocks   => SendBlocks._deserialize(rest)
        case GetBlocks    => GetBlocks._deserialize(rest)
        case SendHeaders  => SendHeaders._deserialize(rest)
        case GetHeaders   => GetHeaders._deserialize(rest)
        case SendTxs      => SendTxs._deserialize(rest)
        case SyncRequest  => SyncRequest._deserialize(rest)
        case SyncResponse => SyncResponse._deserialize(rest)
      }
    }
  }

  def deserialize(input: ByteString)(implicit config: GroupConfig): SerdeResult[Payload] =
    _deserialize(input).flatMap { case Staging(output, rest) =>
      if (rest.isEmpty) {
        Right(output)
      } else {
        Left(SerdeError.redundant(input.size - rest.size, input.size))
      }
    }

  sealed trait Code {
    def codeName: String                   = this.getClass.getSimpleName.dropRight(1)
    lazy val payloadLabeled: Counter.Child = Payload.payloadTotal.labels(codeName)
  }

  trait FixUnused[T <: Payload] {
    def _deserialize(input: ByteString)(implicit config: GroupConfig): SerdeResult[Staging[T]]
  }

  sealed trait Serding[T <: Payload] extends FixUnused[T] {
    protected def serde: Serde[T]

    def serialize(t: T): ByteString = serde.serialize(t)

    def _deserialize(input: ByteString)(implicit config: GroupConfig): SerdeResult[Staging[T]] =
      serde._deserialize(input)
  }

  sealed trait ValidatedSerding[T <: Payload] extends Serding[T] {
    override def _deserialize(
        input: ByteString
    )(implicit config: GroupConfig): SerdeResult[Staging[T]] = {
      serde._deserialize(input).flatMap { case Staging(message, rest) =>
        validate(message) match {
          case Right(_)    => Right(Staging(message, rest))
          case Left(error) => Left(SerdeError.validation(error))
        }
      }
    }

    def validate(t: T)(implicit config: GroupConfig): Either[String, Unit]
  }

  object Code {
    private[message] val values: AVector[Code] =
      AVector(
        Hello,
        Ping,
        Pong,
        SendBlocks,
        GetBlocks,
        SendHeaders,
        GetHeaders,
        SendTxs,
        SyncRequest,
        SyncResponse
      )

    val toInt: Map[Code, Int] = values.toIterable.zipWithIndex.toMap
    def fromInt(code: Int): Option[Code] =
      if (code >= 0 && code < values.length) Some(values(code)) else None
  }

  val payloadTotal: Counter = Counter
    .build(
      "alephium_payload_total",
      "Total number of payloads"
    )
    .labelNames("payload_type")
    .register()
}

sealed trait HandShake extends Payload {
  def version: Int
  def timestamp: TimeStamp
  def brokerInfo: InterBrokerInfo
  def signature: Signature
}

sealed trait HandShakeSerding[T <: HandShake] extends Payload.ValidatedSerding[T] {
  def unsafe(
      version: Int,
      timestamp: TimeStamp,
      brokerInfo: InterBrokerInfo,
      signature: Signature
  ): T

  def unsafe(brokerInfo: InterBrokerInfo, privateKey: PrivateKey): T = {
    val signature = SignatureSchema.sign(brokerInfo.hash.bytes, privateKey)
    unsafe(Protocol.version, TimeStamp.now(), brokerInfo, signature)
  }

  implicit private val brokerSerde: Serde[InterBrokerInfo] = InterBrokerInfo._serde
  val serde: Serde[T] =
    Serde.forProduct4(unsafe, t => (t.version, t.timestamp, t.brokerInfo, t.signature))

  def validate(message: T)(implicit config: GroupConfig): Either[String, Unit] = {
    val validSignature = SignatureSchema.verify(
      message.brokerInfo.hash.bytes,
      message.signature,
      message.brokerInfo.cliqueId.publicKey
    )

    if (
      validSignature &&
      message.version == Protocol.version &&
      message.timestamp > TimeStamp.zero
    ) {
      Right(())
    } else {
      Left(s"invalid HandShake: $message")
    }
  }
}

final case class Hello private (
    version: Int,
    timestamp: TimeStamp,
    brokerInfo: InterBrokerInfo,
    signature: Signature
) extends HandShake {
  override def measure(): Unit = Hello.payloadLabeled.inc()
}

object Hello extends HandShakeSerding[Hello] with Payload.Code {
  def unsafe(
      version: Int,
      timestamp: TimeStamp,
      brokerInfo: InterBrokerInfo,
      signature: Signature
  ): Hello = {
    new Hello(version, timestamp, brokerInfo, signature)
  }
}

final case class Ping(id: RequestId, timestamp: TimeStamp) extends Payload {
  override def measure(): Unit = Ping.payloadLabeled.inc()
}

object Ping extends Payload.Serding[Ping] with Payload.Code {
  val serde: Serde[Ping] = Serde.forProduct2(apply, p => (p.id, p.timestamp))
}

final case class Pong(id: RequestId) extends Payload {
  override def measure(): Unit = Pong.payloadLabeled.inc()
}

object Pong extends Payload.Serding[Pong] with Payload.Code {
  val serde: Serde[Pong] = Serde.forProduct1(apply, p => p.id)
}

final case class SendBlocks(id: Option[RequestId], blocks: AVector[Block]) extends Payload {
  override def measure(): Unit = SendBlocks.payloadLabeled.inc()
}

object SendBlocks extends Payload.Serding[SendBlocks] with Payload.Code {
  implicit val serde: Serde[SendBlocks] = Serde.forProduct2(apply, p => (p.id, p.blocks))
}

final case class GetBlocks(id: RequestId, locators: AVector[BlockHash]) extends Payload {
  override def measure(): Unit = GetBlocks.payloadLabeled.inc()
}

object GetBlocks extends Payload.Serding[GetBlocks] with Payload.Code {
  implicit val serde: Serde[GetBlocks] = Serde.forProduct2(apply, p => (p.id, p.locators))

  def apply(locators: AVector[BlockHash]): GetBlocks = {
    GetBlocks(RequestId.random(), locators)
  }
}

final case class SendHeaders(id: Option[RequestId], headers: AVector[BlockHeader]) extends Payload {
  override def measure(): Unit = SendHeaders.payloadLabeled.inc()
}

object SendHeaders extends Payload.Serding[SendHeaders] with Payload.Code {
  implicit val serde: Serde[SendHeaders] = Serde.forProduct2(apply, p => (p.id, p.headers))
}

final case class GetHeaders(id: RequestId, locators: AVector[BlockHash]) extends Payload {
  override def measure(): Unit = GetHeaders.payloadLabeled.inc()
}

object GetHeaders extends Payload.Serding[GetHeaders] with Payload.Code {
  implicit val serde: Serde[GetHeaders] = Serde.forProduct2(apply, p => (p.id, p.locators))

  def apply(locators: AVector[BlockHash]): GetHeaders = {
    GetHeaders(RequestId.random(), locators)
  }
}

final case class SendTxs(txs: AVector[TransactionTemplate]) extends Payload {
  override def measure(): Unit = SendTxs.payloadLabeled.inc()
}

object SendTxs extends Payload.Serding[SendTxs] with Payload.Code {
  implicit val serde: Serde[SendTxs] = Serde.forProduct1(apply, p => p.txs)
}

final case class SyncRequest(id: RequestId, locators: AVector[AVector[BlockHash]]) extends Payload {
  override def measure(): Unit = SyncRequest.payloadLabeled.inc()
}

object SyncRequest extends Payload.Serding[SyncRequest] with Payload.Code {
  implicit val serde: Serde[SyncRequest] = Serde.forProduct2(apply, p => (p.id, p.locators))

  def apply(locators: AVector[AVector[BlockHash]]): SyncRequest = {
    SyncRequest(RequestId.random(), locators)
  }
}

final case class SyncResponse(id: Option[RequestId], hashes: AVector[AVector[BlockHash]])
    extends Payload {
  override def measure(): Unit = SyncResponse.payloadLabeled.inc()
}

object SyncResponse extends Payload.Serding[SyncResponse] with Payload.Code {
  implicit val serde: Serde[SyncResponse] = Serde.forProduct2(apply, p => (p.id, p.hashes))
}
