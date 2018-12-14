package org.alephium.protocol.message

import akka.util.ByteString
import org.alephium.crypto.Keccak256
import org.alephium.protocol.model.Block
import org.alephium.serde._

import scala.reflect.runtime.universe.{typeOf, TypeTag}
import scala.util.Failure

sealed trait Payload

object Payload {
  val cmdCodes: Map[String, Int] = Map(
    "Ping"       -> 0,
    "Pong"       -> 1,
    "SendBlocks" -> 2,
    "GetBlocks"  -> 3
  )

  implicit val serializer: Serializer[Payload] = {
    case x: Ping       => Serializer[Ping].serialize(x)
    case x: Pong       => Serializer[Pong].serialize(x)
    case x: SendBlocks => Serializer[SendBlocks].serialize(x)
    case x: GetBlocks  => Serializer[GetBlocks].serialize(x)
  }

  def deserializer(cmdCode: Int): Deserializer[Payload] =
    (input: ByteString) =>
      cmdCode match {
        case Ping.cmdCode =>
          Serde[Ping]._deserialize(input)
        case Pong.cmdCode =>
          Serde[Pong]._deserialize(input)
        case SendBlocks.cmdCode =>
          Serde[SendBlocks]._deserialize(input)
        case GetBlocks.cmdCode =>
          Serde[GetBlocks]._deserialize(input)
        case _ => Failure(new WrongFormatException(s"Invalid cmd code: $cmdCode"))
    }
}

class PayloadCompanion[T: TypeTag]() {
  val cmdCode: Int = {
    val name = typeOf[T].typeSymbol.name.toString
    Payload.cmdCodes(name)
  }

  implicit val withCmdCode: PayloadCompanion[T] = this
}

case class Ping(nonce: Int, timestamp: Long) extends Payload

object Ping extends PayloadCompanion[Ping] {
  implicit val serde: Serde[Ping] = Serde.forProduct2(apply, p => (p.nonce, p.timestamp))
}

case class Pong(nonce: Int) extends Payload

object Pong extends PayloadCompanion[Pong] {
  implicit val serde: Serde[Pong] = Serde.forProduct1(apply, p => p.nonce)
}

case class SendBlocks(blocks: Seq[Block]) extends Payload

object SendBlocks extends PayloadCompanion[SendBlocks] {
  implicit val serde: Serde[SendBlocks] = Serde.forProduct1(apply, p => p.blocks)
}

case class GetBlocks(locators: Seq[Keccak256]) extends Payload

object GetBlocks extends PayloadCompanion[GetBlocks] {
  implicit val serde: Serde[GetBlocks] = Serde.forProduct1(apply, p => p.locators)
}
