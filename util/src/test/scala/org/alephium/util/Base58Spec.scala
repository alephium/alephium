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

package org.alephium.util

import akka.util.ByteString
import org.scalatest.Assertion

import org.alephium.util.Hex._

class Base58Spec extends AlephiumSpec {
  it should "encode/decode" in {
    def test(base58: String, raw: ByteString): Assertion = {
      Base58.encode(raw) is base58
      Base58.decode(base58).get is raw
    }

    test("", ByteString.empty)
    test("2g", hex"61")
    test("a3gV", hex"626262")
    test("aPEr", hex"636363")
    test("2cFupjhnEsSn59qHXstmK2ffpLv2", hex"73696d706c792061206c6f6e6720737472696e67")
    test("1NS17iag9jJgTHD1VXjvLCEnZuQ3rJDE9L",
         hex"00eb15231dfceb60925886b67d065299925915aeb172c06647")
    test("ABnLTmg", hex"516b6fcd0f")
    test("3SEo3LWLoPntC", hex"bf4f89001e670274dd")
    test("3EFU7m", hex"572e4794")
    test("EJDM8drfXA6uyA", hex"ecac89cad93923c02321")
    test("Rt5zm", hex"10c8511e")
    test("1111111111", hex"00000000000000000000")
    test("5Hx15HFGyep2CfPxsJKe2fXJsCVn5DEiyoeGGF6JZjGbTRnqfiD",
         hex"801184cd2cdd640ca42cfc3a091c51d549b2f016d454b2774019c2b2d2e08529fd206ec97e")
    test("16UjcYNBG9GTK4uq2f7yYEbuifqCzoLMGS",
         hex"003c176e659bea0f29a3e9bf7880c112b1b31b4dc826268187")
  }

  it should "fail" in {
    def fail(input: String): Assertion = {
      Base58.decode(input).isEmpty is true
    }

    fail("non-base58 string")
    fail("non-base58 alphabet")
    fail("leading whitespace")
    fail("trailing whitespace")
    fail("unexpected character after whitespace")
  }
}
