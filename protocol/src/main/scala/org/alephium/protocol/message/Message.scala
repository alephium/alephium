package org.alephium.protocol.message

import akka.util.ByteString
import org.alephium.protocol.Protocol
import org.alephium.serde._

case class Message(header: Header, payload: Payload)

object Message {
  private def apply(header: Header, payload: Payload): Message =
    new Message(header, payload)

  def apply[T <: Payload](payload: T): Message = {
    val header =
      Header(Protocol.version, Payload.Code.fromValue(payload))
    apply(header, payload)
  }

  implicit val serializer: Serializer[Message] = {
    case Message(header, payload) =>
      Serde[Header].serialize(header) ++
        Payload.serializer.serialize(payload)
  }

  implicit val deserializer: Deserializer[Message] = (input: ByteString) => {
    for {
      (header, hRest)  <- Serde[Header]._deserialize(input)
      (payload, pRest) <- Payload.deserializer(header.cmdCode)._deserialize(hRest)
    } yield {
      (apply(header, payload), pRest)
    }
  }
}
