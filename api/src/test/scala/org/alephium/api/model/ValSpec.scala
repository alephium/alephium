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

package org.alephium.api.model

import org.alephium.api.{ApiModelCodec, JsonFixture}
import org.alephium.json.Json.read
import org.alephium.protocol.Hash
import org.alephium.protocol.model.Address
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{AVector, Hex, I256, U256}

class ValSpec extends ApiModelCodec with JsonFixture {
  def generateContractAddress(): Address.Contract =
    Address.Contract(LockupScript.p2c("uomjgUz6D4tLejTkQtbNJMY8apAjTm1bgQf7em1wDV7S").get)

  it should "encode/decode Val.Bool" in {
    checkData[Val](Val.True, """{"type": "Bool", "value": true}""")
    checkData[Val](Val.False, """{"type": "Bool", "value": false}""")
  }

  it should "encode/decode Val.ByteVec" in {
    val bytes = Hash.generate.bytes
    checkData[Val](
      ValByteVec(bytes),
      s"""{"type": "ByteVec", "value": "${Hex.toHexString(bytes)}"}"""
    )
  }

  it should "encode/decode Val.U256" in {
    checkData[Val](ValU256(U256.MaxValue), s"""{"type": "U256", "value": "${U256.MaxValue}"}""")
    read[Val](s"""{"type": "U256", "value": 1}""") is ValU256(U256.unsafe(1))
  }

  it should "encode/decode Val.I256" in {
    checkData[Val](ValI256(I256.MinValue), s"""{"type": "I256", "value": "${I256.MinValue}"}""")
    read[Val](s"""{"type": "I256", "value": -1}""") is ValI256(I256.unsafe(-1))
  }

  it should "encode/decode Val.Address" in {
    val address = generateContractAddress()
    checkData[Val](
      ValAddress(address),
      s"""{"type": "Address", "value": "${address.toBase58}"}"""
    )
  }

  it should "endcode/decode Val.ValArray" in {
    checkData[Val](
      ValArray(
        AVector[Val](
          Val.True,
          ValU256(U256.Zero),
          ValArray(AVector(Val.True, ValU256(U256.One)))
        )
      ),
      s"""
         |{
         |  "type":"Array",
         |  "value":[
         |    {"type":"Bool","value":true},
         |    {"type":"U256","value":"0"},
         |    {"type":"Array","value":[{"type":"Bool","value":true},{"type":"U256","value":"1"}]}
         |  ]
         |}
         |""".stripMargin
    )
  }
}
