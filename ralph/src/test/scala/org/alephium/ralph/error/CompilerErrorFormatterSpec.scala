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

  // randomly select a slice from the input string.
  // this can also be empty
  private def randomChunk(string: String): Gen[(Int, Int)] =
    for {
      from <- Gen.choose[Int](0, (string.length - 1) max 0)
      to   <- Gen.choose[Int](from, (string.length - 1) max 0)
    } yield (from, to)

  it should "format error messages" in {
    forAll(Gen.alphaNumStr, Gen.alphaNumStr) { case (compilerErrorMessage, errorLine) =>
      // for the error message run multiple tests at random positions and tokens
      forAll(randomChunk(errorLine), Gen.posNum[Int], Gen.alphaNumStr) {
        case ((fromErrorIndex, toErrorIndex), lineNumber, expected) =>
          val found = errorLine.slice(fromErrorIndex, toErrorIndex)

          val sourcePosition =
            SourcePosition(
              rowNum = lineNumber,
              colNum = fromErrorIndex + 1
            )

          val errorMessage =
            CompilerErrorFormatter(
              errorMessage = compilerErrorMessage,
              errorLine = errorLine,
              found = found,
              expected = expected,
              sourcePosition = sourcePosition
            ).format()

          val lines = errorMessage.linesIterator.toArray

          // first line contains the actual full error message
          lines(0) is s"-- error: $compilerErrorMessage"

          // line 2 is the code where the error occurred
          lines(1) is s"$lineNumber |$errorLine"

          // fetch the actual index of the pointer marker
          val indexOfPointer =
            lines(2).indexOf(CompilerErrorFormatter.pointer * found.length)

          // the pointer index should be `[lineNumber][space][bar][spaces][errorToken]`
          val expectedPointerIndex =
            lineNumber.toString.length + 2 + fromErrorIndex

          if (found.isEmpty) {
            indexOfPointer is 0
          } else {
            indexOfPointer is expectedPointerIndex
          }

          val tokenErrorIndex =
            lines(3).indexOf(s"Expected $expected")

          if (expected.isEmpty) {
            tokenErrorIndex is -1
          } else {
            tokenErrorIndex is expectedPointerIndex
          }
      }
    }
  }

  it should "format a custom error message" in {
    val errorMessage =
      CompilerErrorFormatter(
        errorMessage = "detailed compiler error message",
        errorLine = "this is error line",
        found = "error",
        expected = "pass",
        sourcePosition = SourcePosition(2, 9)
      ).format()

    errorMessage is
      """-- error: detailed compiler error message
        |2 |this is error line
        |  |        ^^^^^
        |  |        Expected pass
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

  it should "return empty for when there no actual code to point to" in {
    forAll(Gen.listOf(Gen.alphaNumStr)) { programLines =>
      // This errored line does not exist.
      // This can occurs when there is no closing brace.
      val erroredLineIndex = programLines.length

      val program = programLines.mkString("\n")
      CompilerErrorFormatter.getErroredLine(erroredLineIndex, program) is ""
    }
  }
}
