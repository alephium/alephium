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

package org.alephium.serde

import akka.util.ByteString
import org.scalatest.Assertion

import org.alephium.util.{AlephiumSpec, I256, Random, U256, U32}

class CompactIntegerSpec extends AlephiumSpec {
  it should "encode/decode U32 & U256" in {
    import CompactInteger.Unsigned._

    def test(n: Int): Assertion = {
      val u32 = U32.unsafe(n)
      decodeU32(encode(u32)) isE Staging(u32, ByteString.empty)

      val u256 = U256.unsafe(Integer.toUnsignedLong(n))
      decodeU256(encode(u256)) isE Staging(u256, ByteString.empty)
    }

    (0 until 32).foreach { k =>
      test(1 << k)
      test((1 << k) - 1)
      test((1 << k) + 1)
    }

    decodeU32(encode(U32.MinValue)) isE Staging(U32.MinValue, ByteString.empty)
    decodeU32(encode(U32.MaxValue)) isE Staging(U32.MaxValue, ByteString.empty)
    decodeU256(encode(U256.MinValue)) isE Staging(U256.MinValue, ByteString.empty)
    decodeU256(encode(U256.MaxValue)) isE Staging(U256.MaxValue, ByteString.empty)

    forAll { n: Int =>
      test(n)
    }

    forAll { _: Int =>
      val u256 = Random.nextU256()
      decodeU256(encode(u256)) isE Staging(u256, ByteString.empty)
    }
  }

  it should "encode/decode int" in {
    import CompactInteger.Signed._

    def test(n: Int): Assertion = {
      decodeInt(encodeInt(n)) isE Staging(n, ByteString.empty)
      decodeLong(encodeLong(n.toLong)) isE Staging(n.toLong, ByteString.empty)

      val i256 = I256.from(n)
      decodeI256(encodeI256(i256)) isE Staging(i256, ByteString.empty)
    }

    (0 until 32).foreach { k =>
      test(1 << k)
      test((1 << k) - 1)
      test((1 << k) + 1)
      test(-(1 << k))
      test(-(1 << k) - 1)
      test(-(1 << k) + 1)
    }

    decodeInt(encodeInt(Int.MaxValue)) isE Staging(Int.MaxValue, ByteString.empty)
    decodeInt(encodeInt(Int.MinValue)) isE Staging(Int.MinValue, ByteString.empty)
    decodeLong(encodeLong(Long.MaxValue)) isE Staging(Long.MaxValue, ByteString.empty)
    decodeLong(encodeLong(Long.MinValue)) isE Staging(Long.MinValue, ByteString.empty)
    decodeI256(encodeI256(I256.MaxValue)) isE Staging(I256.MaxValue, ByteString.empty)
    decodeI256(encodeI256(I256.MinValue)) isE Staging(I256.MinValue, ByteString.empty)

    forAll { n: Int =>
      test(n)
    }

    forAll { _: Int =>
      val i256 = Random.nextI256()
      decodeI256(encodeI256(i256)) isE Staging(i256, ByteString.empty)
    }
  }
}
