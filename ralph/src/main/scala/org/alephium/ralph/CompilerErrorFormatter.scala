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
  def apply(failure: Parsed.Failure, program: String): String = {
    val traced = failure.trace()

    val sourcePos =
      SourcePosition.parse(traced.input.prettyIndex(traced.index))

    val pointToCodeLine =
      getErroredLine(sourcePos.rowIndex, program)

    val pointToErrorMessage =
      getLatestErrorMessage(traced)

    val pointToCodeToken =
      dropQuotes(Parsed.Failure.formatTrailing(traced.input, traced.index))

    CompilerErrorFormatter(
      compilerErrorMessage = traced.longMsg,
      pointToCodeLine = pointToCodeLine,
      pointToCodeToken = pointToCodeToken,
      pointToErrorMessage = pointToErrorMessage,
      sourcePosition = sourcePos
    )
  }

  /** Builds a formatted error message.
    *
    * @param compilerErrorMessage
    *   Original error message from compiler/FastParse run.
    * @param pointToCodeLine
    *   Line where this error occurred.
    * @param pointToCodeToken
    *   String token(s) that lead to this failure.
    * @param pointToErrorMessage
    *   Error message to display under the pointer.
    * @param sourcePosition
    *   Location of where this error occurred.
    * @param errorColor
    *   Parts of the error message can be coloured. Use `Some(Console.RED)` as input for red colour.
    *   This parameter is optional so the output is easily comparable in test-cases.
    * @return
    *   A formatted error message.
    */
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def apply(
      compilerErrorMessage: String,
      pointToCodeLine: String,
      pointToCodeToken: String,
      pointToErrorMessage: String,
      sourcePosition: SourcePosition,
      errorColor: Option[String] = None // or use Some(Console.RED)
  ): String = {
    val lineNumGutter   = s"${sourcePosition.rowNum} |"
    val lineNumGutterHL = highlight(lineNumGutter, errorColor)

    val emptyLineNumGutterPaddingLeft = " " * (lineNumGutter.length - 1)
    val emptyLineNumGutter            = highlight(emptyLineNumGutterPaddingLeft + "|", errorColor)

    val errorTag         = highlight("-- error: ", errorColor)
    val mainErrorMessage = highlight(compilerErrorMessage, errorColor)

    val paddingLeft   = " " * sourcePosition.colIndex
    val pointerMarker = this.pointer * pointToCodeToken.length

    // Add padding only if there is a message to append at the end.
    // This makes testing error output easier.
    val paddingLeftPointToErrorMessage = if (pointToErrorMessage.isEmpty) "" else paddingLeft

    s"""$errorTag$mainErrorMessage
       |$lineNumGutterHL$pointToCodeLine
       |$emptyLineNumGutter$paddingLeft$pointerMarker
       |$emptyLineNumGutter$paddingLeftPointToErrorMessage$pointToErrorMessage
       |""".stripMargin
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

  /** Fetch the most recent error message. */
  private def getLatestErrorMessage(traced: Parsed.TracedFailure): String = {
    val lastStackMessage =
      traced.stack
        .filter(_._2 == traced.index) // all parsers for this index
        .lastOption
        .map(_._1)               // use the last errored
        .getOrElse(traced.label) // if none found, use the label

    s"Expected $lastStackMessage"
  }

  /** Wraps the input String to be coloured */
  private def highlight(msg: String, color: Option[String]): String =
    color match {
      case Some(color) =>
        color + msg + Console.RESET

      case None =>
        msg
    }
}
