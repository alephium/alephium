package org.alephium.flow.network.bootstrap

import akka.util.ByteString

import org.alephium.protocol.config.GroupConfig
import org.alephium.serde._

sealed trait Message

object Message {
  final case class Peer(info: PeerInfo)          extends Message
  final case class Clique(info: IntraCliqueInfo) extends Message
  final case class Ack(id: Int)                  extends Message
  case object Ready                              extends Message

  def serialize(input: Message): ByteString = input match {
    case Peer(info)   => ByteString(0) ++ PeerInfo.serialize(info)
    case Clique(info) => ByteString(1) ++ IntraCliqueInfo.serialize(info)
    case Ack(id)      => ByteString(2) ++ intSerde.serialize(id)
    case Ready        => ByteString(3)
  }

  def deserialize(input: ByteString)(
      implicit groupConfig: GroupConfig): SerdeResult[(Message, ByteString)] = {
    byteSerde._deserialize(input).flatMap {
      case (byte, rest) =>
        if (byte == 0) {
          PeerInfo._deserialize(rest).map {
            case (peerInfo, rest) => Peer(peerInfo) -> rest
          }
        } else if (byte == 1) {
          IntraCliqueInfo._deserialize(rest).map {
            case (cliqueInfo, rest) => Clique(cliqueInfo) -> rest
          }
        } else if (byte == 2) {
          intSerde._deserialize(rest).map {
            case (id, rest) => Ack(id) -> rest
          }
        } else if (byte == 3) {
          Right(Ready -> rest)
        } else {
          Left(SerdeError.wrongFormat(s"Invalid bootstrap message code: $byte"))
        }
    }
  }

  def tryDeserialize(data: ByteString)(
      implicit groupConfig: GroupConfig): SerdeResult[Option[(Message, ByteString)]] = {
    SerdeUtils.unwrap(deserialize(data))
  }
}
