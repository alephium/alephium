package org.alephium.protocol.message

import akka.util.ByteString
import org.alephium.crypto.Keccak256
import org.alephium.protocol.model.Block
import org.alephium.serde._

import scala.util.Failure

sealed trait Payload

object Payload {
  implicit val serializer: Serializer[Payload] = {
    case x: Ping       => Serializer[Ping].serialize(x)
    case x: Pong       => Serializer[Pong].serialize(x)
    case x: SendBlocks => Serializer[SendBlocks].serialize(x)
    case x: GetBlocks  => Serializer[GetBlocks].serialize(x)
  }

  def deserializer(cmdCode: Int): Deserializer[Payload] =
    (input: ByteString) => {
      Code.fromInt.get(cmdCode) match {
        case Some(Ping) =>
          Serde[Ping]._deserialize(input)
        case Some(Pong) =>
          Serde[Pong]._deserialize(input)
        case Some(SendBlocks) =>
          Serde[SendBlocks]._deserialize(input)
        case Some(GetBlocks) =>
          Serde[GetBlocks]._deserialize(input)
        case _ => Failure(new WrongFormatException(s"Invalid cmd code: $cmdCode"))
      }
    }

  sealed trait Code
  object Code {
    val values: Seq[Code] = Seq(Ping, Pong, SendBlocks, GetBlocks)

    val toInt: Map[Code, Int]   = values.zipWithIndex.toMap
    val fromInt: Map[Int, Code] = toInt.map(_.swap)
    def fromValue(value: Payload): Code = value match {
      case _: Ping       => Ping
      case _: Pong       => Pong
      case _: SendBlocks => SendBlocks
      case _: GetBlocks  => GetBlocks
    }
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

case class SendBlocks(blocks: Seq[Block]) extends Payload

object SendBlocks extends Payload.Code {
  implicit val serde: Serde[SendBlocks] = Serde.forProduct1(apply, p => p.blocks)
}

case class GetBlocks(locators: Seq[Keccak256]) extends Payload

object GetBlocks extends Payload.Code {
  implicit val serde: Serde[GetBlocks] = Serde.forProduct1(apply, p => p.locators)
}
