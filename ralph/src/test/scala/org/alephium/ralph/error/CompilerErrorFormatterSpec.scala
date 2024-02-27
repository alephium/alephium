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

import org.scalacheck._

import org.alephium.ralph.SourcePosition
import org.alephium.util.AlephiumSpec

class CompilerErrorFormatterSpec extends AlephiumSpec {

  it should "format when error title is empty" in {
    val formatter =
      CompilerErrorFormatter(
        errorTitle = "",
        errorLine = "this is the error line",
        foundLength = "error".length,
        errorMessage = "actual error message",
        errorFooter = None,
        sourcePosition = SourcePosition(2, 13)
      )

    // scalastyle:off whitespace.end.of.line
    formatter.format(None) is
      """-- error (2:13): 
        |2 |this is the error line
        |  |            ^^^^^
        |  |            actual error message
        |""".stripMargin
    // scalastyle:on whitespace.end.of.line

    {
      info("when footer is non-empty")
      val footerFormatted = formatter.copy(errorFooter = Some("Something in the footer"))

      // scalastyle:off whitespace.end.of.line
      footerFormatted.format(None) is
        """-- error (2:13): 
          |2 |this is the error line
          |  |            ^^^^^
          |  |            actual error message
          |  |--------------------------------
          |  |Something in the footer
          |""".stripMargin
      // scalastyle:on whitespace.end.of.line
    }

  }

  it should "format when error line is empty" in {
    val formatter =
      CompilerErrorFormatter(
        errorTitle = "error title",
        errorLine = "",
        foundLength = "error".length,
        errorMessage = "actual error message",
        errorFooter = None,
        sourcePosition = SourcePosition(2, 13)
      )

    formatter.format(None) is
      """-- error (2:13): error title
        |2 |
        |  |            ^^^^^
        |  |            actual error message
        |""".stripMargin

    {
      info("when found length is empty")

      val emptyFoundLength = formatter.copy(foundLength = 0)

      // scalastyle:off whitespace.end.of.line
      emptyFoundLength.format(None) is
        """-- error (2:13): error title
          |2 |
          |  |            
          |  |            actual error message
          |""".stripMargin
      // scalastyle:on whitespace.end.of.line
    }
  }

  it should "format when foundLength (the errored token) is empty" in {
    val formatter =
      CompilerErrorFormatter(
        errorTitle = "error title",
        errorLine = "this is the error line",
        foundLength = "".length,
        errorMessage = "actual error message",
        errorFooter = None,
        sourcePosition = SourcePosition(2, 13)
      )

    // scalastyle:off whitespace.end.of.line
    formatter.format(None) is
      """-- error (2:13): error title
        |2 |this is the error line
        |  |            
        |  |            actual error message
        |""".stripMargin
    // scalastyle:on whitespace.end.of.line
  }

  it should "format when error message is empty" in {
    val formatter =
      CompilerErrorFormatter(
        errorTitle = "error title",
        errorLine = "this is the error line",
        foundLength = "error".length,
        errorMessage = "",
        errorFooter = None,
        sourcePosition = SourcePosition(2, 13)
      )

    formatter.format(None) is
      """-- error (2:13): error title
        |2 |this is the error line
        |  |            ^^^^^
        |  |
        |""".stripMargin

    {
      info("when found length is 1")

      val emptyFoundLength = formatter.copy(foundLength = 1)

      emptyFoundLength.format(None) is
        """-- error (2:13): error title
          |2 |this is the error line
          |  |            ^
          |  |
          |""".stripMargin
    }
  }

  it should "format when all fields are empty (not throw exceptions)" in {
    val formatter =
      CompilerErrorFormatter(
        errorTitle = "",
        errorLine = "",
        foundLength = "".length,
        errorMessage = "",
        errorFooter = None,
        sourcePosition = SourcePosition(0, 0)
      )

    // scalastyle:off whitespace.end.of.line
    formatter.format(None) is
      """-- error (0:0): 
        |0 |
        |  |
        |  |
        |""".stripMargin
    // scalastyle:on whitespace.end.of.line

    {
      info("when footer is non-empty")
      val footerFormatted = formatter.copy(errorFooter = Some("Something in the footer"))

      // scalastyle:off whitespace.end.of.line
      footerFormatted.format(None) is
        """-- error (0:0): 
          |0 |
          |  |
          |  |
          |  |-----------------------
          |  |Something in the footer
          |""".stripMargin
      // scalastyle:on whitespace.end.of.line
    }

    {
      info("when footer is Some(empty), it should not fail abruptly (not throw an exception)")
      val footerFormatted = formatter.copy(errorFooter = Some(""))

      // scalastyle:off whitespace.end.of.line
      footerFormatted.format(None) is
        """-- error (0:0): 
          |0 |
          |  |
          |  |
          |  |-------------
          |  |
          |""".stripMargin
      // scalastyle:on whitespace.end.of.line
    }
  }

  it should "format when all fields are empty, except the actual error message" in {
    val formatter =
      CompilerErrorFormatter(
        errorTitle = "",
        errorLine = "",
        foundLength = 0,
        errorMessage = "the actual error message",
        errorFooter = None,
        sourcePosition = SourcePosition(2, 1)
      )

    // scalastyle:off whitespace.end.of.line
    formatter.format(None) is
      """-- error (2:1): 
        |2 |
        |  |
        |  |the actual error message
        |""".stripMargin
    // scalastyle:on whitespace.end.of.line

    {
      info("when footer is non-empty")
      val footerFormatted = formatter.copy(errorFooter = Some("Something in the footer"))

      // scalastyle:off whitespace.end.of.line
      footerFormatted.format(None) is
        """-- error (2:1): 
          |2 |
          |  |
          |  |the actual error message
          |  |------------------------
          |  |Something in the footer
          |""".stripMargin
      // scalastyle:on whitespace.end.of.line
    }
  }

  it should "format when SourcePosition.column is the first character" in {
    val formatter =
      CompilerErrorFormatter(
        errorTitle = "error title",
        errorLine = "this is the error line",
        foundLength = 1,
        errorMessage = "failed here",
        errorFooter = None,
        sourcePosition = SourcePosition(1, 1)
      )

    formatter.format(None) is
      """-- error (1:1): error title
        |1 |this is the error line
        |  |^
        |  |failed here
        |""".stripMargin

    {
      info("when found length is extended")

      formatter.copy(foundLength = 4).format(None) is
        """-- error (1:1): error title
          |1 |this is the error line
          |  |^^^^
          |  |failed here
          |""".stripMargin
    }
  }

  it should "format when SourcePosition.column is the last character" in {
    val errorLine = "this is the error line"

    val formatter =
      CompilerErrorFormatter(
        errorTitle = "error title",
        errorLine = errorLine,
        foundLength = 1,
        errorMessage = "failed here",
        errorFooter = None,
        sourcePosition = SourcePosition(1, errorLine.length)
      )

    formatter.format(None) is
      """-- error (1:22): error title
        |1 |this is the error line
        |  |                     ^
        |  |                     failed here
        |""".stripMargin

    {
      info("when found length is extended")

      formatter.copy(foundLength = 6).format(None) is
        """-- error (1:22): error title
          |1 |this is the error line
          |  |                     ^
          |  |                     failed here
          |""".stripMargin
    }
  }

  it should "apply padding when SourcePosition.row is multi-digit Integer" in {
    val formatter =
      CompilerErrorFormatter(
        errorTitle = "error title",
        errorLine = "this is the error line",
        foundLength = 1,
        errorMessage = "failed here",
        errorFooter = None,
        sourcePosition = SourcePosition(100, 1)
      )

    formatter.format(None) is
      """-- error (100:1): error title
        |100 |this is the error line
        |    |^
        |    |failed here
        |""".stripMargin
  }

  it should "format when source position is invalid (should not throw Exceptions)" in {
    val formatter =
      CompilerErrorFormatter(
        errorTitle = "error title",
        errorLine = "this is the error line",
        foundLength = 4,
        errorMessage = "something went wrong",
        errorFooter = None,
        sourcePosition = SourcePosition(-1, -1)
      )

    formatter.format(None) is
      """-- error (-1:-1): error title
        |-1 |this is the error line
        |   |^^^^
        |   |something went wrong
        |""".stripMargin
  }

  it should "format when all field are non-empty & valid" in {
    val formatter =
      CompilerErrorFormatter(
        errorTitle = "The title",
        errorLine = "this is error line",
        foundLength = "error".length,
        errorMessage = "actual error message",
        errorFooter = Some("I have some explaining to do"),
        sourcePosition = SourcePosition(2, 9)
      )

    formatter.format() is
      """-- error (2:9): The title
        |2 |this is error line
        |  |        ^^^^^
        |  |        actual error message
        |  |----------------------------
        |  |I have some explaining to do
        |""".stripMargin

    {
      info("when footer does not exist")

      formatter.copy(errorFooter = None).format() is
        """-- error (2:9): The title
          |2 |this is error line
          |  |        ^^^^^
          |  |        actual error message
          |""".stripMargin
    }
  }

  it should "format when the length of the footer line is the smallest among the other lines" in {
    val formatter =
      CompilerErrorFormatter(
        errorTitle = "error title",
        errorLine = "this is the error line",
        foundLength = "line".length,
        errorMessage = "failed here",
        errorFooter = Some("Footer"),
        sourcePosition = SourcePosition(1, 19)
      )

    formatter.format(None) is
      """-- error (1:19): error title
        |1 |this is the error line
        |  |                  ^^^^
        |  |                  failed here
        |  |-----------------------------
        |  |Footer
        |""".stripMargin
  }

  it should "format when the length of the footer line is the largest among the other lines" in {
    val formatter =
      CompilerErrorFormatter(
        errorTitle = "error title",
        errorLine = "this is the error line",
        foundLength = "line".length,
        errorMessage = "failed here",
        errorFooter = Some("This is some chunky footer message to impart wisdom."),
        sourcePosition = SourcePosition(1, 19)
      )

    formatter.format(None) is
      """-- error (1:19): error title
        |1 |this is the error line
        |  |                  ^^^^
        |  |                  failed here
        |  |----------------------------------------------------
        |  |This is some chunky footer message to impart wisdom.
        |""".stripMargin
  }

  it should "get errored line" in {
    forAll(Gen.nonEmptyListOf(Gen.alphaNumStr)) { programLines =>
      val program = programLines.mkString("\n")

      forAll(Gen.choose(0, programLines.length - 1)) { lineIndex =>
        CompilerErrorFormatter.getErroredLine(lineIndex, program) is programLines(lineIndex)
      }
    }
  }

  it should "return empty when there is no actual code to point to" in {
    forAll(Gen.listOf(Gen.alphaNumStr)) { programLines =>
      // This errored line does not exist.
      // This can occurs when there is no closing brace.
      val erroredLineIndex = programLines.length

      val program = programLines.mkString("\n")
      CompilerErrorFormatter.getErroredLine(erroredLineIndex, program) is ""
    }
  }
}
