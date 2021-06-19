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

package org.alephium.flow.mining

import java.math.BigInteger

import akka.util.ByteString

import org.alephium.protocol.config.GroupConfigFixture
import org.alephium.serde.Staging
import org.alephium.util.{AlephiumSpec, AVector}
import org.alephium.util.Hex.HexStringSyntax

class MessageSpec extends AlephiumSpec with GroupConfigFixture.Default {
  "ClientMessage" should "serde properly" in {
    val message    = SubmitBlock(hex"bbbb")
    val serialized = ClientMessage.serialize(message)
    ClientMessage.tryDeserialize(serialized).rightValue.get is
      Staging(message, ByteString.empty)
    ClientMessage.tryDeserialize(serialized.init).rightValue is None
    ClientMessage.tryDeserialize(serialized ++ serialized.init).rightValue.get is
      Staging(message, serialized.init)
  }

  "ServerMessage" should "serde properly" in {
    val messages = Seq(
      Jobs(AVector(Job(0, 1, hex"aa", hex"bb", BigInteger.ZERO))),
      SubmitResult(0, 1, true),
      SubmitResult(0, 1, false)
    )
    val serializeds = messages.map(ServerMessage.serialize)
    messages.zip(serializeds).foreach { case (message, serialized) =>
      ServerMessage.tryDeserialize(serialized).rightValue.get is
        Staging(message, ByteString.empty)
      ServerMessage.tryDeserialize(serialized.init).rightValue is None
      ServerMessage.tryDeserialize(serialized ++ serialized.init).rightValue.get is
        Staging(message, serialized.init)
    }
  }
}
