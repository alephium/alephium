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

import org.alephium.ralph.error.{CompilerError, CompilerErrorFormatter, FastParseErrorUtil}
import org.alephium.ralph.util.OperatingSystem
import org.alephium.util._

// scalastyle:off no.equal file.size.limit
class StatelessParserSpec extends AlephiumSpec {
  val StatelessParser = new StatelessParser(None)

  it should "disallow mutable template params" in {
    def createProgram(params: String) =
      s"""
         |// comment
         |AssetScript Foo($params) {
         |  pub fn bar(a: U256, b: U256) -> (U256) {
         |    return (a + b)
         |  }
         |}
         |""".stripMargin

    {
      info("when first param is mutable")
      val program = createProgram("mut x: U256, y: U256, z: U256")

      val failure =
        intercept[CompilerError.`Expected an immutable variable`] {
          fastparse.parse(program, StatelessParser.assetScript(_))
        }

      val formatter = failure.toFormatter(program)

      formatter is
        CompilerErrorFormatter(
          errorTitle = "Syntax error",
          errorLine = program.linesIterator.toList(2),
          foundLength = 3,
          errorMessage = "Expected an immutable variable",
          errorFooter = None,
          sourcePosition = SourcePosition(3, 17)
        )

      // formatter should point the exact mut declaration
      formatter.format() is
        s"""-- error (3:17): Syntax error
           |3 |AssetScript Foo(mut x: U256, y: U256, z: U256) {
           |  |                ^^^
           |  |                Expected an immutable variable
           |""".stripMargin
    }

    {
      info("when second param is mutable")
      val program = createProgram("x: U256, mut y: U256, z: U256")

      val failure =
        intercept[CompilerError.`Expected an immutable variable`] {
          fastparse.parse(program, StatelessParser.assetScript(_))
        }

      val formatter = failure.toFormatter(program)

      formatter is
        CompilerErrorFormatter(
          errorTitle = "Syntax error",
          errorLine = program.linesIterator.toList(2),
          foundLength = 3,
          errorMessage = "Expected an immutable variable",
          errorFooter = None,
          sourcePosition = SourcePosition(3, 26)
        )

      // formatter should point the exact mut declaration
      formatter.format() is
        s"""-- error (3:26): Syntax error
           |3 |AssetScript Foo(x: U256, mut y: U256, z: U256) {
           |  |                         ^^^
           |  |                         Expected an immutable variable
           |""".stripMargin
    }
  }

  it should "report missing closing brace" in {
    val program =
      s"""
         |// comment
         |AssetScript Foo {
         |  pub fn bar(a: U256, b: U256) -> (U256) {
         |    return (a + b)
         |  }
         |""".stripMargin

    val failure =
      fastparse
        .parse(program, StatelessParser.assetScript(_))
        .asInstanceOf[Parsed.Failure]
        .trace()

    val formatter = FastParseErrorUtil(failure).toFormatter()

    formatter is
      CompilerErrorFormatter(
        errorTitle = "Syntax error",
        errorMessage = """Expected "}"""",
        errorLine = "",
        foundLength = 2,
        errorFooter = Some("""Trace log: Expected assetScript:1:1 / "}":7:1, found """""),
        sourcePosition = SourcePosition(7, 1)
      )

    formatter.format() is
      s"""-- error (7:1): Syntax error
         |7 |
         |  |^^
         |  |Expected "}"
         |  |-------------------------------------------------------
         |  |Trace log: Expected assetScript:1:1 / "}":7:1, found ""
         |""".stripMargin
  }

  it should "not allow definitions after end of program" in {
    val program =
      s"""
         |AssetScript Foo {
         |  pub fn bar() -> U256 {
         |    return 1
         |  }
         |}
         |
         |Blah
         |""".stripMargin

    val error =
      intercept[CompilerError.ExpectedEndOfInput](
        fastparse.parse(program, StatelessParser.assetScript(_))
      )

    val indexOfB = program.indexOf("Blah")
    error is CompilerError.ExpectedEndOfInput('B', indexOfB, None)

    if (!OperatingSystem.isWindows) {
      error.format(program) is
        """-- error (8:1): Syntax error
          |8 |Blah
          |  |^
          |  |Expected end of input but found unexpected character 'B'
          |  |-------------------------------------------------------------------------------------------
          |  |Help: Ralph programs should end with a closing brace `}` to indicate the end of code block.
          |""".stripMargin
    }
  }
}
