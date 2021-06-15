// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.flow.network.bootstrap

import akka.util.ByteString

import org.alephium.protocol.config.GroupConfig
import org.alephium.serde._

sealed trait Message

object Message extends SimpleSerde[Message] {
  final case class Peer(info: PeerInfo)          extends Message
  final case class Clique(info: IntraCliqueInfo) extends Message
  final case class Ack(id: Int)                  extends Message
  case object Ready                              extends Message

  def serializeBody(input: Message): ByteString = {
    input match {
      case Peer(info)   => ByteString(0) ++ PeerInfo.serialize(info)
      case Clique(info) => ByteString(1) ++ IntraCliqueInfo.serialize(info)
      case Ack(id)      => ByteString(2) ++ intSerde.serialize(id)
      case Ready        => ByteString(3)
    }
  }

  def deserializeBody(
      input: ByteString
  )(implicit groupConfig: GroupConfig): SerdeResult[Message] = {
    byteSerde._deserialize(input).flatMap { case Staging(byte, rest) =>
      if (byte == 0) {
        PeerInfo.deserialize(rest).map(Peer)
      } else if (byte == 1) {
        IntraCliqueInfo.deserialize(rest).map(Clique)
      } else if (byte == 2) {
        intSerde.deserialize(rest).map(Ack)
      } else if (byte == 3) {
        Right(Ready)
      } else {
        Left(SerdeError.wrongFormat(s"Invalid bootstrap message code: $byte"))
      }
    }
  }
}
