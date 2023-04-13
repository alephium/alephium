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
    forAll(Gen.alphaNumStr, Gen.alphaNumStr, Gen.alphaNumStr, Gen.option(Gen.alphaNumStr)) {
      case (errorTitle, errorMessage, errorLine, explain) =>
        // for the error message run multiple tests at random positions and tokens
        forAll(randomChunk(errorLine), Gen.posNum[Int]) {
          case ((fromErrorIndex, toErrorIndex), lineNumber) =>
            val found = errorLine.slice(fromErrorIndex, toErrorIndex)

            val sourcePosition =
              SourcePosition(
                rowNum = lineNumber,
                colNum = fromErrorIndex + 1
              )

            val formatterMessage =
              CompilerErrorFormatter(
                errorTitle = errorTitle,
                errorLine = errorLine,
                foundLength = found.length,
                errorMessage = errorMessage,
                errorFooter = explain,
                sourcePosition = sourcePosition
              ).format()

            val lines = formatterMessage.linesIterator.toArray

            // first line contains the actual full error message
            lines(0) is s"-- error (${sourcePosition.rowNum}:${sourcePosition.colNum}): $errorTitle"

            // line 2 is the code where the error occurred
            lines(1) is s"$lineNumber |$errorLine"

            // fetch the actual index of the pointer marker
            val indexOfPointer =
              lines(2).indexOf(CompilerErrorFormatter.pointer * found.length)

            val gutterLength =
              lineNumber.toString.length + 2

            // the pointer index should be `[lineNumber][space][bar][spaces][errorToken]`
            val expectedPointerIndex =
              gutterLength + fromErrorIndex

            if (found.isEmpty) {
              indexOfPointer is 0
            } else {
              indexOfPointer is expectedPointerIndex
            }

            val tokenErrorIndex =
              lines(3).indexOf(errorMessage)

            if (errorMessage.isEmpty) {
              tokenErrorIndex is 0
            } else {
              tokenErrorIndex is expectedPointerIndex
            }

            // length of the bar should be maximum length of a line
            explain match {
              case Some(explain) =>
                val maxLine = lines.foldLeft(0)(_ max _.length)
                val bars    = "-" * (maxLine - gutterLength)

                lines(4).indexOf(bars) is gutterLength
                lines(5).lastIndexOf(explain) is gutterLength

                lines.length is 6 // no more lines to process

              case None =>
                lines.length is 4 // No more lines to process
            }

        }
    }
  }

  it should "format a custom error message" in {
    val formatter =
      CompilerErrorFormatter(
        errorTitle = "The title",
        errorLine = "this is error line",
        foundLength = "error".length,
        errorMessage = "actual error message",
        errorFooter = None,
        sourcePosition = SourcePosition(2, 9)
      )

    {
      info("when footer exists")
      val formatterWithExplain =
        formatter.copy(errorFooter = Some("I have some explaining to do"))

      formatterWithExplain.format() is
        """-- error (2:9): The title
          |2 |this is error line
          |  |        ^^^^^
          |  |        actual error message
          |  |----------------------------
          |  |I have some explaining to do
          |""".stripMargin
    }

    {
      info("when footer does not exist")

      formatter.format() is
        """-- error (2:9): The title
          |2 |this is error line
          |  |        ^^^^^
          |  |        actual error message
          |""".stripMargin
    }
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
