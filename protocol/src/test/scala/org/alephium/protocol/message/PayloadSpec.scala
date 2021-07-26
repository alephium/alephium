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
import org.alephium.protocol.SignatureSchema
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.message.Payload.Code
import org.alephium.protocol.model.{BrokerInfo, CliqueId}
import org.alephium.serde.SerdeError
import org.alephium.util.{AlephiumSpec, AVector}

class PayloadSpec extends AlephiumSpec {
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
}
