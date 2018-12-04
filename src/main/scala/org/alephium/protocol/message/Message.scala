package org.alephium.protocol.message

import akka.util.ByteString
import org.alephium.constant.Protocol
import org.alephium.serde._

case class Message(header: Header, payload: Payload)

object Message {
  private def apply(header: Header, payload: Payload): Message =
    new Message(header, payload)

  def apply[T <: Payload](payload: T)(implicit withCmdCode: PayloadCompanion[T]): Message = {
    val header = Header(Protocol.version, withCmdCode.cmdCode)
    apply(header, payload)
  }

  implicit val serializer: Serializer[Message] = {
    case Message(header, payload) =>
      implicitly[Serde[Header]].serialize(header) ++
        Payload.serializer.serialize(payload)
  }

  implicit val deserializer: Deserializer[Message] = (input: ByteString) => {
    for {
      (header, hRest)  <- implicitly[Serde[Header]]._deserialize(input)
      (payload, pRest) <- Payload.deserializer(header.cmdCode)._deserialize(hRest)
    } yield (apply(header, payload), pRest)
  }
}
