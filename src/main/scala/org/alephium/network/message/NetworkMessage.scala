package org.alephium.network.message

import akka.util.ByteString
import org.alephium.serde._

case class NetworkMessage(header: NetworkHeader, payload: NetworkPayload)

object NetworkMessage {

  def apply[T <: NetworkPayload](version: Int, payload: T)(
      implicit withCmdCode: NetworkPayloadCompanion[T]): NetworkMessage = {
    val header = NetworkHeader(version, withCmdCode.cmdCode)
    NetworkMessage(header, payload)
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
    } yield (NetworkMessage(header, payload), pRest)
  }
}
