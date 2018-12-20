package org.alephium.protocol.message

import akka.util.ByteString
import org.alephium.crypto.Keccak256
import org.alephium.protocol.Protocol
import org.alephium.protocol.model.{Block, BlockHeader, PeerId}
import org.alephium.serde._
import org.alephium.util.AVector

sealed trait Payload

object Payload {
  def serialize(payload: Payload): ByteString = {
    val (code, data) = payload match {
      case x: Hello       => (Hello, Serializer[Hello].serialize(x))
      case x: HelloAck    => (HelloAck, Serializer[HelloAck].serialize(x))
      case x: Ping        => (Ping, Serializer[Ping].serialize(x))
      case x: Pong        => (Pong, Serializer[Pong].serialize(x))
      case x: SendBlocks  => (SendBlocks, Serializer[SendBlocks].serialize(x))
      case x: GetBlocks   => (GetBlocks, Serializer[GetBlocks].serialize(x))
      case x: SendHeaders => (SendHeaders, Serializer[SendHeaders].serialize(x))
      case x: GetHeaders  => (GetHeaders, Serializer[GetHeaders].serialize(x))
    }
    Serde[Int].serialize(Code.toInt(code)) ++ data
  }

  val deserializerCode: Deserializer[Code] =
    Serde[Int].validateGet(Code.fromInt, c => s"Invalid code $c")

  def _deserialize(input: ByteString): Either[SerdeError, (Payload, ByteString)] = {
    deserializerCode._deserialize(input).flatMap {
      case (code, rest) =>
        code match {
          case Hello       => Serde[Hello]._deserialize(rest)
          case HelloAck    => Serde[HelloAck]._deserialize(rest)
          case Ping        => Serde[Ping]._deserialize(rest)
          case Pong        => Serde[Pong]._deserialize(rest)
          case SendBlocks  => Serde[SendBlocks]._deserialize(rest)
          case GetBlocks   => Serde[GetBlocks]._deserialize(rest)
          case SendHeaders => Serde[SendHeaders]._deserialize(rest)
          case GetHeaders  => Serde[GetHeaders]._deserialize(rest)
        }
    }
  }

  sealed trait Code
  object Code {
    private val values: AVector[Code] =
      AVector(Hello, HelloAck, Ping, Pong, SendBlocks, GetBlocks, SendHeaders, GetHeaders)

    val toInt: Map[Code, Int] = values.toIterable.zipWithIndex.toMap
    def fromInt(code: Int): Option[Code] =
      if (code >= 0 && code < values.length) Some(values(code)) else None
  }
}

case class Hello(version: Int, timestamp: Long, peerId: PeerId) extends Payload {
  def validate: Boolean = version == Protocol.version
}

object Hello extends Payload.Code {
  implicit val serde: Serde[Hello] =
    Serde.forProduct3(apply, p => (p.version, p.timestamp, p.peerId))

  def apply(peerId: PeerId): Hello = {
    Hello(Protocol.version, System.currentTimeMillis(), peerId)
  }
}

case class HelloAck(version: Int, timestamp: Long, peerId: PeerId) extends Payload {
  def validate: Boolean = version == Protocol.version
}

object HelloAck extends Payload.Code {
  implicit val serde: Serde[HelloAck] =
    Serde.forProduct3(apply, p => (p.version, p.timestamp, p.peerId))

  def apply(peerId: PeerId): HelloAck = {
    HelloAck(Protocol.version, System.currentTimeMillis(), peerId)
  }
}

case class Ping(nonce: Int, timestamp: Long) extends Payload

object Ping extends Payload.Code {
  implicit val serde: Serde[Ping] = Serde.forProduct2(apply, p => (p.nonce, p.timestamp))
}

case class Pong(nonce: Int) extends Payload

object Pong extends Payload.Code {
  implicit val serde: Serde[Pong] = Serde.forProduct1(apply, p => p.nonce)
}

case class SendBlocks(blocks: AVector[Block]) extends Payload

object SendBlocks extends Payload.Code {
  implicit val serde: Serde[SendBlocks] = Serde.forProduct1(apply, p => p.blocks)
}

case class GetBlocks(locators: AVector[Keccak256]) extends Payload

object GetBlocks extends Payload.Code {
  implicit val serde: Serde[GetBlocks] = Serde.forProduct1(apply, p => p.locators)
}

case class SendHeaders(headers: AVector[BlockHeader]) extends Payload

object SendHeaders extends Payload.Code {
  implicit val serde: Serde[SendHeaders] = Serde.forProduct1(apply, p => p.headers)
}

case class GetHeaders(locators: AVector[Keccak256]) extends Payload

object GetHeaders extends Payload.Code {
  implicit val serde: Serde[GetHeaders] = Serde.forProduct1(apply, p => p.locators)
}
