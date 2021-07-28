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

import java.net.InetSocketAddress

import org.alephium.macros.EnumerationMacros
import org.alephium.protocol.{Protocol, PublicKey, SignatureSchema}
import org.alephium.protocol.config.{GroupConfig, GroupConfigFixture}
import org.alephium.protocol.message.Payload.Code
import org.alephium.protocol.model.{BrokerInfo, CliqueId}
import org.alephium.serde.SerdeError
import org.alephium.util.{AlephiumSpec, AVector, Hex, TimeStamp}

class PayloadSpec extends AlephiumSpec with GroupConfigFixture.Default {
  implicit val ordering: Ordering[Code] = Ordering.by(Code.toInt(_))
  implicit val groupConfig = new GroupConfig {
    override def groups: Int = 4
  }

  it should "index all payload types" in {
    val codes = EnumerationMacros.sealedInstancesOf[Code]
    Code.values is AVector.from(codes)
  }

  it should "validate Hello message" in {
    val address            = new InetSocketAddress("127.0.0.1", 0)
    val (priKey1, pubKey1) = SignatureSchema.secureGeneratePriPub()
    val (priKey2, _)       = SignatureSchema.secureGeneratePriPub()
    val brokerInfo         = BrokerInfo.unsafe(CliqueId(pubKey1), 0, 1, address)

    val validInput  = Hello.unsafe(brokerInfo.interBrokerInfo, priKey1)
    val validOutput = Hello._deserialize(Hello.serde.serialize(validInput))
    validOutput.map(_.value) isE validInput

    val invalidInput  = Hello.unsafe(brokerInfo.interBrokerInfo, priKey2)
    val invalidOutput = Hello._deserialize(Hello.serde.serialize(invalidInput))
    invalidOutput.leftValue is a[SerdeError]
  }

  it should "serialize/deserialize the Hello payload" in {
    import Hex._

    val publicKeyHex = hex"4b8abc82e1423c4aa234549a3ada5dbc04ce1bc8db1b990c4af3b73fdfd7b301f4"
    val cliqueId     = CliqueId(new PublicKey(publicKeyHex))
    val brokerInfo   = BrokerInfo.unsafe(cliqueId, 0, 1, new InetSocketAddress("127.0.0.1", 0))
    val version: Int = Protocol.version
    val hello        = Hello.unsafe(version, TimeStamp.unsafe(100), brokerInfo.interBrokerInfo)
    val helloBlob    =
      // code id
      hex"00" ++
        // version
        hex"4809" ++
        // timestamp
        hex"0000000000000064" ++
        // clique id
        publicKeyHex ++
        // borker id
        hex"00" ++
        // groupNumPerBroker
        hex"01"

    Payload.serialize(hello) is helloBlob
    Payload.deserialize(helloBlob) isE hello
  }

  it should "serialize/deserialize the Ping/Pong payload" in {
    import Hex._

    val requestId = RequestId.unsafe(1)
    val ping      = Ping(requestId, TimeStamp.unsafe(100))
    val pingBlob  =
      // code id
      hex"01" ++
        // request id
        hex"01" ++
        // timestamp
        hex"0000000000000064"

    Payload.serialize(ping) is pingBlob
    Payload.deserialize(pingBlob) isE ping

    val pong     = Pong(requestId)
    val pongBlob =
      // code id
      hex"02" ++
        // request id
        hex"01"

    Payload.serialize(pong) is pongBlob
    Payload.deserialize(pongBlob) isE pong
  }
}
