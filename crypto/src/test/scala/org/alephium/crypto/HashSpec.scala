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

package org.alephium.crypto

import akka.util.ByteString

import org.alephium.serde._
import org.alephium.util.AlephiumSpec
import org.alephium.util.Hex._

class HashSpec extends AlephiumSpec {
  def check[T <: RandomBytes](provider: HashSchema[T], tests: Seq[(String, ByteString)])(implicit
      serde: Serde[T]
  ): Unit = {
    provider.getClass.getSimpleName should "hash correctly" in {
      for ((message, expected) <- tests) {
        val output = provider.hash(message)
        output.bytes is expected
      }
    }

    it should "serde correctly" in {
      for ((message, _) <- tests) {
        val input  = provider.hash(message)
        val output = deserialize[T](serialize(input)).toOption.get
        output is input
      }
    }

    it should "compute hashCode correctly" in {
      for ((message, inHex) <- tests) {
        val output  = BigInt(provider.hash(message).hashCode())
        val expcted = BigInt(inHex.takeRight(4).toArray)
        output is expcted
      }
    }

    it should "do arithmic correctly" in {
      provider.xor(provider.zero, provider.zero) is provider.zero
      provider.xor(provider.zero, provider.allOne) is provider.allOne
      provider.xor(provider.allOne, provider.zero) is provider.allOne
      provider.xor(provider.allOne, provider.allOne) is provider.zero
      provider.zero.toRandomIntUnsafe is 0
      provider.allOne.toRandomIntUnsafe is (0xffffffffL * 8).toInt
    }
  }

  check(
    Sha256,
    Seq(
      "Hello World1" -> hex"6D1103674F29502C873DE14E48E9E432EC6CF6DB76272C7B0DAD186BB92C9A9A",
      "Hello World2" -> hex"0DE69F56365C10550D05E65AE8229DD0686F7894A807830DAEC8CAA879731F4D",
      "Hello World3" -> hex"DCE9D9544A7261B593A31C0C780BC2A4320D9E8D1CB98F02BAB71B722D741A99",
      "Hello World4" -> hex"8EC7C601D35E6059762698EE611D91AD150862DA29A3BA30365FC97AF2872E8E",
      "Hello World5" -> hex"5ADBF693486E51AA4C5BDBA658CD1E4040248063BDB3BE37FADE4DBDD398A5E0"
    )
  )

  check(
    Keccak256,
    Seq(
      "Hello World1" -> hex"2744686CE50A2A5AE2A94D18A3A51149E2F21F7EEB4178DE954A2DFCADC21E3C",
      "Hello World2" -> hex"8143E0ED4DC593777C3E496013C8BE2D95875E9FCA46E81FC218CBA480E9A25F",
      "Hello World3" -> hex"1F92B7907A9564266AB8D351117090B96BE9D7B3C22172DA122106E253F61810",
      "Hello World4" -> hex"BA647048F3B513C233E3EBB60EC3B0122B49CE4D0A433254E03D54C1EE617D90",
      "Hello World5" -> hex"6CC1E7EC6FE939BB0EF7A10FE3D03DF052387B3CAF1416FD1CBAE3EF5C45657A"
    )
  )

  check(
    Blake2b,
    Seq(
      "Hello World1" -> hex"8947bee8a082f643a8ceab187d866e8ec0be8c2d7d84ffa8922a6db77644b37a",
      "Hello World2" -> hex"4712c917c8ab6451d2c0adcc6af62926fec2d7ef1ee6a75282fca261b9a7b6a6",
      "Hello World3" -> hex"8f4c9c1048dc913ce1d3b031a7c52f108023385f2c240c390000b06c911064b6",
      "Hello World4" -> hex"b60cf83ffeb99da4b250ec680f7402bba6a6c75f3bdae16ef7ef0b4b762a9f4b",
      "Hello World5" -> hex"06bacc3a528511c9066a576813e80698e041f513ac478d31dd79b39e378f0dfe"
    )
  )

  check(
    Blake3,
    Seq(
      "Hello World1" -> hex"17d3b5965e96993f57c248a2067fae8e39c52b004dc8a486045d123843fa95ed",
      "Hello World2" -> hex"02754dd285e9cf1930db28406eeffba69446878a7a1df4d7ce5454f7cb685313",
      "Hello World3" -> hex"68ec63e22d53c5bcdc5184206ad5cadec61b6ef974eba86be52bea136794a029",
      "Hello World4" -> hex"832b8318705417c7f83404bd83bcfaeab5cc75b9eda78f0f0c7a127375332480",
      "Hello World5" -> hex"5096d2f8343d337bacda7678fb05074995a63b3c0bcd19b83b9ba5ed4b96a3db"
    )
  )

  check(
    Sha3,
    Seq(
      "Hello World1" -> hex"f5ad69e6b85ae4a51264df200c2bd19fbc337e4160c77dfaa1ea98cbae8ed743",
      "Hello World2" -> hex"4b45d4eaabeedeeb8be379ab90d220d1ee0d1a8376df40ae6f1d0edac55ca577",
      "Hello World3" -> hex"fd2dc2cd7e5347f4c2907e1619bdebd01b09c7e513a0224d7f22be57ec1987f1",
      "Hello World4" -> hex"e96ebfa7958dd086b1f68d2a0181c4199c0552298d7b8053c5ff8fe1bd67188e",
      "Hello World5" -> hex"f00b336bacb0a533670463d3e116a7a30c1786074b1e204a1ec0124d0837b938"
    )
  )

  it should "check double hash" in {
    def test[T <: RandomBytes](hasher: HashSchema[T], bytes: Seq[Byte]) = {
      hasher.doubleHash(bytes) is hasher.hash(hasher.hash(bytes).bytes)
    }
    forAll { bytes: Seq[Byte] =>
      test(Sha256, bytes)
      test(Keccak256, bytes)
      test(Blake2b, bytes)
      test(Sha3, bytes)
      test(Blake3, bytes)
    }
  }
}
