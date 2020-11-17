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

package org.alephium.protocol.message

import org.alephium.protocol.config.GroupConfig
import org.alephium.serde._
import org.alephium.util.AlephiumSpec

class MessageSpec extends AlephiumSpec {

  it should "serde message" in {

    implicit val groupConfig: GroupConfig = new GroupConfig {
      override def groups: Int = 4
    }

    val pong    = Pong(1)
    val message = Message(pong)

    val payloadLength = Payload.serialize(message.payload).length
    val headerLength  = Header.serde.serialize(message.header).length

    payloadLength is 8
    headerLength is 4

    Message.serialize(message).length is (payloadLength + headerLength + 4)

    Message.deserialize(Message.serialize(message)).toOption.get is message
  }

  it should "fail to deserialize if length isn't correct" in {

    implicit val groupConfig: GroupConfig = new GroupConfig {
      override def groups: Int = 4
    }

    val pong    = Pong(1)
    val message = Message(pong)
    val payload = Payload.serialize(pong)
    val header  = serdeImpl[Header].serialize(message.header)

    // scalastyle:off no.equal
    (-10 to 10).filterNot(_ == payload.length).map(intSerde.serialize).foreach { length =>
      Message.deserialize(header ++ length ++ payload).isLeft is true
    }
  }
}
