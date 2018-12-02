package org.alephium.network.message

import akka.util.ByteString
import org.alephium.serde._

import scala.reflect.runtime.universe.{TypeTag, typeOf}
import scala.util.Failure

sealed trait NetworkPayload

object NetworkPayload {
  val cmdCodes: Map[String, Int] = Map(
    "Ping" -> 0,
    "Pong" -> 1
  )

  implicit val serializer: Serializer[NetworkPayload] = {
    case x: Ping => implicitly[Serializer[Ping]].serialize(x)
    case x: Pong => implicitly[Serializer[Pong]].serialize(x)
  }

  def deserializer(cmdCode: Int): Deserializer[NetworkPayload] =
    (input: ByteString) =>
      cmdCode match {
        case Ping.withCmdCode.cmdCode =>
          implicitly[Serde[Ping]]._deserialize(input)
        case Pong.withCmdCode.cmdCode =>
          implicitly[Serde[Pong]]._deserialize(input)
        case _ => Failure(OtherError(s"Invalid cmd code: $cmdCode"))
    }
}

class NetworkPayloadCompanion[T: TypeTag]() {
  val cmdCode: Int = {
    val name = typeOf[T].typeSymbol.name.toString
    NetworkPayload.cmdCodes(name)
  }
}

case class Ping(nonce: Int) extends NetworkPayload

object Ping extends NetworkPayloadCompanion[Ping] {
  implicit val withCmdCode: NetworkPayloadCompanion[Ping] = new NetworkPayloadCompanion[Ping] {}
}

case class Pong(nonce: Int) extends NetworkPayload

object Pong {
  implicit val withCmdCode: NetworkPayloadCompanion[Pong] = new NetworkPayloadCompanion[Pong] {}
}
