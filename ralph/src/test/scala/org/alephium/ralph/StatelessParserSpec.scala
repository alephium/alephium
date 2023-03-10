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
package org.alephium.ralph

import fastparse.Parsed

import org.alephium.protocol.vm.*
import org.alephium.util.*

// scalastyle:off no.equal file.size.limit
class StatelessParserSpec extends AlephiumSpec with ContextGenerators {

  it should "disallow mutable template params" in {
    def createProgram(params: String) =
      s"""
         |// comment
         |AssetScript Foo($params) {
         |  pub fn bar(: U256, b: U256) -> (U256) {
         |    return (a + b)
         |  }
         |}
         |""".stripMargin

    {
      info("when first param is mutable")
      val program = createProgram("mut x: U256, y: U256, z: U256")

      val failure =
        fastparse
          .parse(program, StatelessParser.assetScript(_))
          .asInstanceOf[Parsed.Failure]
          .trace()

      val expectedErrorMessage =
        s"""Expected ${CompilerError.AnImmutableVariable.message}:3:17 / (letter | digit | "_"):3:16, found "(mut x: U2""""

      failure.longMsg is expectedErrorMessage

      // formatter should point the exact mut declaration
      CompilerErrorFormatter(failure, program) is
        s"""-- error: $expectedErrorMessage
           |3 |AssetScript Foo(mut x: U256, y: U256, z: U256) {
           |  |                ^^^^^^^^^^
           |  |                Expected an immutable variable
           |""".stripMargin
    }

    {
      info("when second param is mutable")
      val program = createProgram("x: U256, mut y: U256, z: U256")

      val failure =
        fastparse
          .parse(program, StatelessParser.assetScript(_))
          .asInstanceOf[Parsed.Failure]
          .trace()

      // TODO: The found "(x: U256, " reported in FastParse `longMsg` is not showing the actual
      //       `mut` variable but is showing the start of the token.
      val expectedErrorMessage =
        s"""Expected ${CompilerError.AnImmutableVariable.message}:3:26 / (letter | digit | "_"):3:16, found "(x: U256, """"

      failure.longMsg is expectedErrorMessage

      // formatter should point the exact mut declaration
      CompilerErrorFormatter(failure, program) is
        s"""-- error: $expectedErrorMessage
           |3 |AssetScript Foo(x: U256, mut y: U256, z: U256) {
           |  |                         ^^^^^^^^^^
           |  |                         Expected an immutable variable
           |""".stripMargin
    }
  }

}
