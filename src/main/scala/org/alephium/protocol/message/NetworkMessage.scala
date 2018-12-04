package org.alephium.protocol.message

import akka.util.ByteString
import org.alephium.constant.Protocol
import org.alephium.serde._

case class NetworkMessage(header: NetworkHeader, payload: NetworkPayload)

object NetworkMessage {
  private def apply(header: NetworkHeader, payload: NetworkPayload): NetworkMessage =
    new NetworkMessage(header, payload)

  def apply[T <: NetworkPayload](payload: T)(
      implicit withCmdCode: NetworkPayloadCompanion[T]): NetworkMessage = {
    val header = NetworkHeader(Protocol.version, withCmdCode.cmdCode)
    apply(header, payload)
  }

  implicit val serializer: Serializer[NetworkMessage] = {
    case NetworkMessage(header, payload) =>
      implicitly[Serde[NetworkHeader]].serialize(header) ++
        NetworkPayload.serializer.serialize(payload)
  }

  implicit val deserializer: Deserializer[NetworkMessage] = (input: ByteString) => {
    for {
      (header, hRest)  <- implicitly[Serde[NetworkHeader]]._deserialize(input)
      (payload, pRest) <- NetworkPayload.deserializer(header.cmdCode)._deserialize(hRest)
    } yield (apply(header, payload), pRest)
  }
}
