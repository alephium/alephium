package org.alephium.protocol.message

import akka.util.ByteString
import org.alephium.crypto.Keccak256
import org.alephium.protocol.model.{Block, BlockHeader}
import org.alephium.serde._
import org.alephium.util.AVector

import scala.util.Failure

sealed trait Payload

object Payload {
  implicit val serializer: Serializer[Payload] = {
    case x: Ping        => Serializer[Ping].serialize(x)
    case x: Pong        => Serializer[Pong].serialize(x)
    case x: SendBlocks  => Serializer[SendBlocks].serialize(x)
    case x: GetBlocks   => Serializer[GetBlocks].serialize(x)
    case x: SendHeaders => Serializer[SendHeaders].serialize(x)
    case x: GetHeaders  => Serializer[GetHeaders].serialize(x)
  }

  def deserializer(cmdCode: Int): Deserializer[Payload] =
    (input: ByteString) => {
      Code.fromInt(cmdCode) match {
        case Some(code) =>
          code match {
            case Ping =>
              Serde[Ping]._deserialize(input)
            case Pong =>
              Serde[Pong]._deserialize(input)
            case SendBlocks =>
              Serde[SendBlocks]._deserialize(input)
            case GetBlocks =>
              Serde[GetBlocks]._deserialize(input)
            case SendHeaders =>
              Serde[SendHeaders]._deserialize(input)
            case GetHeaders =>
              Serde[GetHeaders]._deserialize(input)
          }
        case None => Failure(new WrongFormatException(s"Invalid cmd code: $cmdCode"))
      }
    }

  sealed trait Code
  object Code {
    val values: AVector[Code] = AVector(Ping, Pong, SendBlocks, GetBlocks, SendHeaders, GetHeaders)
    val toInt: Map[Code, Int] = values.toIterable.zipWithIndex.toMap

    def fromValue(value: Payload): Int = value match {
      case _: Ping        => toInt(Ping)
      case _: Pong        => toInt(Pong)
      case _: SendBlocks  => toInt(SendBlocks)
      case _: GetBlocks   => toInt(GetBlocks)
      case _: SendHeaders => toInt(SendHeaders)
      case _: GetHeaders  => toInt(GetHeaders)
    }

    def fromInt(code: Int): Option[Code] =
      if (code >= 0 && code < values.length) Some(values(code)) else None
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

case class GetHeaders(locators: AVector[BlockHeader]) extends Payload

object GetHeaders extends Payload.Code {
  implicit val serde: Serde[GetHeaders] = Serde.forProduct1(apply, p => p.locators)
}
