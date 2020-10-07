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

import org.alephium.util.Hex._

class HexSpec extends AlephiumSpec {
  it should "interpolate string correctly" in {
    val input = hex"666f6f626172"
    input.map(_.toChar).mkString("") is "foobar"
  }

  it should "decode correctly" in {
    val input    = "666f6f626172"
    val expected = ByteString.fromString("foobar")
    unsafe(input) is expected
    from(input).get is expected
  }

  it should "throw an error while decoding invalid input" in {
    val input = "666f6f62617"
    from(input).isEmpty is true
  }
}
