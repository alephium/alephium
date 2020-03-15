package org.alephium.protocol.message

import scala.language.existentials

import akka.util.ByteString

import org.alephium.crypto.Keccak256
import org.alephium.protocol.Protocol
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.serde._
import org.alephium.util.AVector

sealed trait Payload

object Payload {
  def serialize(payload: Payload): ByteString = {
    val (code, data) = payload match {
      case x: Hello       => (Hello, Hello.serialize(x))
      case x: HelloAck    => (HelloAck, HelloAck.serialize(x))
      case x: Ping        => (Ping, Ping.serialize(x))
      case x: Pong        => (Pong, Pong.serialize(x))
      case x: SendBlocks  => (SendBlocks, SendBlocks.serialize(x))
      case x: GetBlocks   => (GetBlocks, GetBlocks.serialize(x))
      case x: SendHeaders => (SendHeaders, SendHeaders.serialize(x))
      case x: GetHeaders  => (GetHeaders, GetHeaders.serialize(x))
      case x: SendTxs     => (SendTxs, SendTxs.serialize(x))
    }
    intSerde.serialize(Code.toInt(code)) ++ data
  }

  val deserializerCode: Deserializer[Code[_]] =
    intSerde.validateGet(Code.fromInt, c => s"Invalid code $c")

  def _deserialize(input: ByteString)(
      implicit config: GroupConfig): SerdeResult[(Payload, ByteString)] = {
    deserializerCode._deserialize(input).flatMap {
      case (code, rest) =>
        code match {
          case Hello       => Hello._deserialize(rest)
          case HelloAck    => HelloAck._deserialize(rest)
          case Ping        => Ping._deserialize(rest)
          case Pong        => Pong._deserialize(rest)
          case SendBlocks  => SendBlocks._deserialize(rest)
          case GetBlocks   => GetBlocks._deserialize(rest)
          case SendHeaders => SendHeaders._deserialize(rest)
          case GetHeaders  => GetHeaders._deserialize(rest)
          case SendTxs     => SendTxs._deserialize(rest)
        }
    }
  }

  trait FixUnused[T] {
    def _deserialize(input: ByteString)(implicit config: GroupConfig): SerdeResult[(T, ByteString)]
  }

  sealed trait Code[T] extends FixUnused[T] {
    protected def serde: Serde[T]

    def serialize(t: T): ByteString = serde.serialize(t)

    def _deserialize(input: ByteString)(
        implicit config: GroupConfig): SerdeResult[(T, ByteString)] =
      serde._deserialize(input)
  }

  sealed trait ValidatedCode[T] extends Code[T] {
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
    private[message] val values: AVector[Code[_]] =
      AVector(Hello, HelloAck, Ping, Pong, SendBlocks, GetBlocks, SendHeaders, GetHeaders, SendTxs)

    val toInt: Map[Code[_], Int] = values.toIterable.zipWithIndex.toMap
    def fromInt(code: Int): Option[Code[_]] =
      if (code >= 0 && code < values.length) Some(values(code)) else None
  }
}

sealed trait HandShake extends Payload {
  val version: Int
  val timestamp: Long
  val cliqueId: CliqueId
  val brokerInfo: BrokerInfo
}

sealed trait HandShakeCode[T <: HandShake] extends Payload.ValidatedCode[T] {
  def unsafe(version: Int, timestamp: Long, cliqueId: CliqueId, brokerInfo: BrokerInfo): T

  def unsafe(cliqueId: CliqueId, brokerInfo: BrokerInfo): T =
    unsafe(Protocol.version, System.currentTimeMillis(), cliqueId, brokerInfo)

  private implicit val brokerSerde: Serde[BrokerInfo] = BrokerInfo._serde
  val serde: Serde[T] =
    Serde.forProduct4(unsafe, t => (t.version, t.timestamp, t.cliqueId, t.brokerInfo))

  def validate(message: T)(implicit config: GroupConfig): Either[String, Unit] =
    if (message.version == Protocol.version && message.timestamp > 0) Right(())
    else Left(s"invalid HandShake: $message")
}

sealed abstract case class Hello(
    version: Int,
    timestamp: Long,
    cliqueId: CliqueId,
    brokerInfo: BrokerInfo
) extends HandShake

object Hello extends HandShakeCode[Hello] {
  def unsafe(version: Int, timestamp: Long, cliqueId: CliqueId, brokerInfo: BrokerInfo): Hello =
    new Hello(version, timestamp, cliqueId, brokerInfo) {}
}

sealed abstract case class HelloAck(
    version: Int,
    timestamp: Long,
    cliqueId: CliqueId,
    brokerInfo: BrokerInfo
) extends HandShake

object HelloAck extends HandShakeCode[HelloAck] {
  def unsafe(version: Int, timestamp: Long, cliqueId: CliqueId, brokerInfo: BrokerInfo): HelloAck =
    new HelloAck(version, timestamp, cliqueId, brokerInfo) {}
}

final case class Ping(nonce: Int, timestamp: Long) extends Payload

object Ping extends Payload.Code[Ping] {
  val serde: Serde[Ping] = Serde.forProduct2(apply, p => (p.nonce, p.timestamp))
}

final case class Pong(nonce: Int) extends Payload

object Pong extends Payload.Code[Pong] {
  val serde: Serde[Pong] = Serde.forProduct1(apply, p => p.nonce)
}

final case class SendBlocks(blocks: AVector[Block]) extends Payload

object SendBlocks extends Payload.Code[SendBlocks] {
  implicit val serde: Serde[SendBlocks] = Serde.forProduct1(apply, p => p.blocks)
}

final case class GetBlocks(locators: AVector[Keccak256]) extends Payload

object GetBlocks extends Payload.Code[GetBlocks] {
  implicit val serde: Serde[GetBlocks] = Serde.forProduct1(apply, p => p.locators)
}

final case class SendHeaders(headers: AVector[BlockHeader]) extends Payload

object SendHeaders extends Payload.Code[SendHeaders] {
  implicit val serde: Serde[SendHeaders] = Serde.forProduct1(apply, p => p.headers)
}

final case class GetHeaders(locators: AVector[Keccak256]) extends Payload

object GetHeaders extends Payload.Code[GetHeaders] {
  implicit val serde: Serde[GetHeaders] = Serde.forProduct1(apply, p => p.locators)
}

final case class SendTxs(txs: AVector[Transaction]) extends Payload

object SendTxs extends Payload.Code[SendTxs] {
  implicit val serde: Serde[SendTxs] = Serde.forProduct1(apply, p => p.txs)
}
