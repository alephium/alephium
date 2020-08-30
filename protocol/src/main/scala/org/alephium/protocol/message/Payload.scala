package org.alephium.protocol.message

import akka.util.ByteString

import org.alephium.protocol.Hash
import org.alephium.protocol.Protocol
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.serde._
import org.alephium.util.AVector

sealed trait Payload

object Payload {
  @SuppressWarnings(
    Array("org.wartremover.warts.Product",
          "org.wartremover.warts.Serializable",
          "org.wartremover.warts.JavaSerializable"))
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

  def _deserialize(input: ByteString)(
      implicit config: GroupConfig): SerdeResult[(Payload, ByteString)] = {
    deserializerCode._deserialize(input).flatMap {
      case (code, rest) =>
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

  sealed trait Code

  trait FixUnused[T] {
    def _deserialize(input: ByteString)(implicit config: GroupConfig): SerdeResult[(T, ByteString)]
  }

  sealed trait Serding[T] extends FixUnused[T] {
    protected def serde: Serde[T]

    def serialize(t: T): ByteString = serde.serialize(t)

    def _deserialize(input: ByteString)(
        implicit config: GroupConfig): SerdeResult[(T, ByteString)] =
      serde._deserialize(input)
  }

  sealed trait ValidatedSerding[T] extends Serding[T] {
    override def _deserialize(input: ByteString)(
        implicit config: GroupConfig): SerdeResult[(T, ByteString)] = {
      serde._deserialize(input).flatMap {
        case (message, rest) =>
          validate(message) match {
            case Right(_)    => Right((message, rest))
            case Left(error) => Left(SerdeError.validation(error))
          }
      }
    }

    def validate(t: T)(implicit config: GroupConfig): Either[String, Unit]
  }

  object Code {
    private[message] val values: AVector[Code] =
      AVector(Hello,
              Ping,
              Pong,
              SendBlocks,
              GetBlocks,
              SendHeaders,
              GetHeaders,
              SendTxs,
              SyncRequest,
              SyncResponse)

    val toInt: Map[Code, Int] = values.toIterable.zipWithIndex.toMap
    def fromInt(code: Int): Option[Code] =
      if (code >= 0 && code < values.length) Some(values(code)) else None
  }
}

sealed trait HandShake extends Payload {
  val version: Int
  val timestamp: Long
  val brokerInfo: BrokerInfo
}

sealed trait HandShakeSerding[T <: HandShake] extends Payload.ValidatedSerding[T] {
  def unsafe(version: Int, timestamp: Long, brokerInfo: BrokerInfo): T

  def unsafe(brokerInfo: BrokerInfo): T =
    unsafe(Protocol.version, System.currentTimeMillis(), brokerInfo)

  private implicit val brokerSerde: Serde[BrokerInfo] = BrokerInfo._serde
  val serde: Serde[T] =
    Serde.forProduct3(unsafe, t => (t.version, t.timestamp, t.brokerInfo))

  def validate(message: T)(implicit config: GroupConfig): Either[String, Unit] =
    if (message.version == Protocol.version && message.timestamp > 0) Right(())
    else Left(s"invalid HandShake: $message")
}

final case class Hello private (
    version: Int,
    timestamp: Long,
    brokerInfo: BrokerInfo
) extends HandShake

object Hello extends HandShakeSerding[Hello] with Payload.Code {
  def unsafe(version: Int, timestamp: Long, brokerInfo: BrokerInfo): Hello =
    new Hello(version, timestamp, brokerInfo)
}

final case class Ping(nonce: Int, timestamp: Long) extends Payload

object Ping extends Payload.Serding[Ping] with Payload.Code {
  val serde: Serde[Ping] = Serde.forProduct2(apply, p => (p.nonce, p.timestamp))
}

final case class Pong(nonce: Int) extends Payload

object Pong extends Payload.Serding[Pong] with Payload.Code {
  val serde: Serde[Pong] = Serde.forProduct1(apply, p => p.nonce)
}

final case class SendBlocks(blocks: AVector[Block]) extends Payload

object SendBlocks extends Payload.Serding[SendBlocks] with Payload.Code {
  implicit val serde: Serde[SendBlocks] = Serde.forProduct1(apply, p => p.blocks)
}

final case class GetBlocks(locators: AVector[Hash]) extends Payload

object GetBlocks extends Payload.Serding[GetBlocks] with Payload.Code {
  implicit val serde: Serde[GetBlocks] = Serde.forProduct1(apply, p => p.locators)
}

final case class SendHeaders(headers: AVector[BlockHeader]) extends Payload

object SendHeaders extends Payload.Serding[SendHeaders] with Payload.Code {
  implicit val serde: Serde[SendHeaders] = Serde.forProduct1(apply, p => p.headers)
}

final case class GetHeaders(locators: AVector[Hash]) extends Payload

object GetHeaders extends Payload.Serding[GetHeaders] with Payload.Code {
  implicit val serde: Serde[GetHeaders] = Serde.forProduct1(apply, p => p.locators)
}

final case class SendTxs(txs: AVector[Transaction]) extends Payload

object SendTxs extends Payload.Serding[SendTxs] with Payload.Code {
  implicit val serde: Serde[SendTxs] = Serde.forProduct1(apply, p => p.txs)
}

final case class SyncRequest(locators: AVector[AVector[Hash]]) extends Payload

object SyncRequest extends Payload.Serding[SyncRequest] with Payload.Code {
  implicit val serde: Serde[SyncRequest] = Serde.forProduct1(apply, p => p.locators)
}

final case class SyncResponse(hashes: AVector[AVector[Hash]]) extends Payload

object SyncResponse extends Payload.Serding[SyncResponse] with Payload.Code {
  implicit val serde: Serde[SyncResponse] = Serde.forProduct1(apply, p => p.hashes)
}
