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

import fastparse._

/** Builds a formatted error message.
  *
  * @param errorMessage
  *   Original error message from compiler/FastParse run.
  * @param errorLine
  *   Line where this error occurred.
  * @param found
  *   String token(s) that lead to this failure.
  * @param expected
  *   Error message to display under the pointer.
  * @param sourcePosition
  *   Location of where this error occurred.
  */

final case class CompilerErrorFormatter(
    errorMessage: String,
    errorLine: String,
    found: String,
    expected: String,
    sourcePosition: SourcePosition
) {

  import CompilerErrorFormatter._

  /** Formats the error message.
    *
    * @param errorColor
    *   Parts of the error message can be coloured. Use `Some(Console.RED)` as input for red colour.
    *   This parameter is optional so the output is easily comparable in test-cases.
    * @return
    *   A formatted error message.
    */
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def format(errorColor: Option[String] = None): String = { // or use Some(Console.RED)
    val lineNumGutter   = s"${sourcePosition.rowNum} |"
    val lineNumGutterHL = highlight(lineNumGutter, errorColor)

    val emptyLineNumGutterPaddingLeft = " " * (lineNumGutter.length - 1)
    val emptyLineNumGutter            = highlight(emptyLineNumGutterPaddingLeft + "|", errorColor)

    val errorTag         = highlight("-- error: ", errorColor)
    val mainErrorMessage = highlight(errorMessage, errorColor)

    val paddingLeft   = " " * sourcePosition.colIndex
    val pointerMarker = CompilerErrorFormatter.pointer * found.length

    // Add padding & expected only if there is a message to append at the end.
    val paddingLeftExpected =
      if (expected.isEmpty) {
        ""
      } else {
        s"${paddingLeft}Expected "
      }

    s"""$errorTag$mainErrorMessage
       |$lineNumGutterHL$errorLine
       |$emptyLineNumGutter$paddingLeft$pointerMarker
       |$emptyLineNumGutter$paddingLeftExpected$expected
       |""".stripMargin
  }

}

object CompilerErrorFormatter {

  val pointer = "^"

  /** Builds an error message from FastParser's `Parsed.Failure` result.
    *
    * @param failure
    *   FastParser's failure run result.
    * @param program
    *   The compiled program/source.
    * @return
    *   A formatted error message.
    */
  def apply(failure: Parsed.Failure): CompilerErrorFormatter =
    CompilerErrorFormatter(failure.trace())

  def apply(traced: Parsed.TracedFailure): CompilerErrorFormatter = {
    val program =
      traced.input.slice(0, traced.input.length)

    val erroredIndex =
      getErroredIndex(traced)

    val sourcePosition =
      SourcePosition.parse(traced.input.prettyIndex(erroredIndex))

    val errorLine =
      getErroredLine(sourcePosition.rowIndex, program)

    val expected =
      getLatestErrorMessage(traced, erroredIndex)

    val foundQuoted =
      Parsed.Failure.formatTrailing(traced.input, erroredIndex)

    val foundNoQuotes =
      dropQuotes(foundQuoted)

    val errorMessage =
      traced.longMsg

    CompilerErrorFormatter(
      errorMessage = errorMessage,
      errorLine = errorLine,
      found = foundNoQuotes,
      expected = expected,
      sourcePosition = sourcePosition
    )
  }

  /** Fetch the line where the error occurred.
    *
    * @param programRowIndex
    *   Index of where the error occurred. Note: this is an index so it starts at `0`.
    * @param program
    *   The compiled program.
    * @return
    *   The line that errored or empty string if the given `programRowIndex` does not exist.
    */
  def getErroredLine(programRowIndex: Int, program: String): String = {
    val lines = program.linesIterator.toArray
    if (programRowIndex >= lines.length) {
      // Meaning: it's the end of the program and there is no actual code to point to.
      // For example: missing closing brace on a contract.
      ""
    } else {
      lines(programRowIndex)
    }
  }

  /** Removes wrapper quotes from the output by `Parsed.Failure.formatTrailing`. */
  def dropQuotes(string: String): String =
    string.replaceFirst("""^"(.+)"$""", "$1")

  /** Use index with maximum value i.e. the latest errored code */
  private def getErroredIndex(traced: Parsed.TracedFailure): Int =
    traced.index max traced.stack.foldLeft(0)(_ max _._2)

  /** Fetch the most recent error message. */
  private def getLatestErrorMessage(traced: Parsed.TracedFailure, forIndex: Int): String =
    traced.stack
      .filter(_._2 == forIndex) // all parsers for this index
      .lastOption
      .map(_._1)               // use the last errored
      .getOrElse(traced.label) // if none found, use the label

  /** Wraps the input String to be coloured */
  private def highlight(msg: String, color: Option[String]): String =
    color match {
      case Some(color) =>
        color + msg + Console.RESET

      case None =>
        msg
    }
}
