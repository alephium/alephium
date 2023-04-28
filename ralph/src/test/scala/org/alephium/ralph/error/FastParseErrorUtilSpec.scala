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

package org.alephium.ralph.error

import fastparse._

import org.alephium.ralph.Lexer
import org.alephium.util.AlephiumSpec

class FastParseErrorUtilSpec extends AlephiumSpec {

  it should "drop only the head and tail quotes" in {
    FastParseErrorUtil.dropQuotes("\"test\"") is "test"
    FastParseErrorUtil.dropQuotes("\"\"test\"\"") is "\"test\""
    FastParseErrorUtil.dropQuotes("\"\"test\"") is "\"test"
    FastParseErrorUtil.dropQuotes("\"test\"\"") is "test\""
    FastParseErrorUtil.dropQuotes("\"\"test\"\"test\"\"") is "\"test\"\"test\""
  }

  it should "not drop quotes if both or either head or tail quote is missing" in {
    FastParseErrorUtil.dropQuotes("\"test") is "\"test"
    FastParseErrorUtil.dropQuotes("test\"") is "test\""
    FastParseErrorUtil.dropQuotes("test") is "test"
  }

  it should "return label when input index does not exist" in {
    val input  = "a"
    val traced = fastparse.parse(input, Lexer.typedNum(_)).asInstanceOf[Parsed.Failure].trace()

    // stack does not contain index `1`
    traced.stack is
      List(
        (CompilerError.`an I256 or U256 value`.message, 0),
        ("num", 0)
      )

    // index `1` will return the label
    FastParseErrorUtil
      .getLatestErrorMessage(
        traced = traced,
        forIndex = 1
      ) is traced.label
  }
}
