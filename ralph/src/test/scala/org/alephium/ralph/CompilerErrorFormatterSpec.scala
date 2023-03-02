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

import org.scalacheck.*

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
    forAll(Gen.alphaNumStr, Gen.alphaNumStr) { case (compilerErrorMessage, pointToCodeLine) =>
      // for the error message run multiple tests at random positions and tokens
      forAll(randomChunk(pointToCodeLine), Gen.posNum[Int], Gen.alphaNumStr) {
        case ((fromErrorIndex, toErrorIndex), lineNumber, pointToErrorMessage) =>
          val pointToCodeToken = pointToCodeLine.slice(fromErrorIndex, toErrorIndex)

          val sourcePosition =
            SourcePosition(
              rowNum = lineNumber,
              colNum = fromErrorIndex + 1
            )

          val errorMessage =
            CompilerErrorFormatter(
              compilerErrorMessage = compilerErrorMessage,
              pointToCodeLine = pointToCodeLine,
              pointToCodeToken = pointToCodeToken,
              pointToErrorMessage = pointToErrorMessage,
              sourcePosition = sourcePosition,
              errorColor = None
            )

          val lines = errorMessage.linesIterator.toArray

          // first line contains the actual full error message
          lines(0) is s"-- error: $compilerErrorMessage"

          // line 2 is the code where the error occurred
          lines(1) is s"$lineNumber |$pointToCodeLine"

          // fetch the actual index of the pointer marker
          val indexOfPointer =
            lines(2).indexOf(CompilerErrorFormatter.pointer * pointToCodeToken.length)

          // the pointer index should be `[lineNumber][space][bar][spaces][errorToken]`
          val expectedPointerIndex =
            lineNumber.toString.length + 2 + fromErrorIndex

          if (pointToCodeToken.nonEmpty) {
            indexOfPointer is expectedPointerIndex
          } else {
            indexOfPointer is 0
          }

          val tokenErrorIndex =
            lines(3).indexOf(pointToErrorMessage)

          if (pointToErrorMessage.nonEmpty) {
            tokenErrorIndex is expectedPointerIndex
          } else {
            tokenErrorIndex is 0
          }

      }
    }
  }

  it should "drop only the head and tail quotes" in {
    CompilerErrorFormatter.dropQuotes("\"test\"") is "test"
    CompilerErrorFormatter.dropQuotes("\"\"test\"\"") is "\"test\""
    CompilerErrorFormatter.dropQuotes("\"\"test\"") is "\"test"
    CompilerErrorFormatter.dropQuotes("\"test\"\"") is "test\""
    CompilerErrorFormatter.dropQuotes("\"\"test\"\"test\"\"") is "\"test\"\"test\""
  }

  it should "not drop quotes if both or either head or tail quote is missing" in {
    CompilerErrorFormatter.dropQuotes("\"test") is "\"test"
    CompilerErrorFormatter.dropQuotes("test\"") is "test\""
    CompilerErrorFormatter.dropQuotes("test") is "test"
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
