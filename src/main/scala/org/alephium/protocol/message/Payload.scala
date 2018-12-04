package org.alephium.protocol.message

import akka.util.ByteString
import org.alephium.serde._

import scala.reflect.runtime.universe.{TypeTag, typeOf}
import scala.util.Failure

sealed trait Payload

object Payload {
  val cmdCodes: Map[String, Int] = Map(
    "Ping" -> 0,
    "Pong" -> 1
  )

  implicit val serializer: Serializer[Payload] = {
    case x: Ping => implicitly[Serializer[Ping]].serialize(x)
    case x: Pong => implicitly[Serializer[Pong]].serialize(x)
  }

  def deserializer(cmdCode: Int): Deserializer[Payload] =
    (input: ByteString) =>
      cmdCode match {
        case Ping.withCmdCode.cmdCode =>
          implicitly[Serde[Ping]]._deserialize(input)
        case Pong.withCmdCode.cmdCode =>
          implicitly[Serde[Pong]]._deserialize(input)
        case _ => Failure(OtherError(s"Invalid cmd code: $cmdCode"))
    }
}

class PayloadCompanion[T: TypeTag]() {
  val cmdCode: Int = {
    val name = typeOf[T].typeSymbol.name.toString
    Payload.cmdCodes(name)
  }
}

case class Ping(nonce: Int) extends Payload

object Ping extends PayloadCompanion[Ping] {
  implicit val withCmdCode: PayloadCompanion[Ping] = new PayloadCompanion[Ping] {}
  implicit val serde: Serde[Ping]                  = Serde.forProduct1(apply, p => p.nonce)
}

case class Pong(nonce: Int) extends Payload

object Pong {
  implicit val withCmdCode: PayloadCompanion[Pong] = new PayloadCompanion[Pong] {}
  implicit val serde: Serde[Pong]                  = Serde.forProduct1(apply, p => p.nonce)
}
