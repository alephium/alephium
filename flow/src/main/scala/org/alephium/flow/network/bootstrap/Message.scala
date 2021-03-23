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

object Message {
  final case class Peer(info: PeerInfo)          extends Message
  final case class Clique(info: IntraCliqueInfo) extends Message
  final case class Ack(id: Int)                  extends Message
  case object Ready                              extends Message

  def serialize(input: Message): ByteString =
    input match {
      case Peer(info)   => ByteString(0) ++ PeerInfo.serialize(info)
      case Clique(info) => ByteString(1) ++ IntraCliqueInfo.serialize(info)
      case Ack(id)      => ByteString(2) ++ intSerde.serialize(id)
      case Ready        => ByteString(3)
    }

  def deserialize(
      input: ByteString
  )(implicit groupConfig: GroupConfig): SerdeResult[Staging[Message]] = {
    byteSerde._deserialize(input).flatMap { case Staging(byte, rest) =>
      if (byte == 0) {
        PeerInfo._deserialize(rest).map(_.mapValue(Peer(_)))
      } else if (byte == 1) {
        IntraCliqueInfo._deserialize(rest).map(_.mapValue(Clique(_)))
      } else if (byte == 2) {
        intSerde._deserialize(rest).map(_.mapValue(Ack(_)))
      } else if (byte == 3) {
        Right(Staging(Ready, rest))
      } else {
        Left(SerdeError.wrongFormat(s"Invalid bootstrap message code: $byte"))
      }
    }
  }

  def tryDeserialize(
      data: ByteString
  )(implicit groupConfig: GroupConfig): SerdeResult[Option[Staging[Message]]] = {
    SerdeUtils.unwrap(deserialize(data))
  }
}
