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

import org.alephium.ralph.SourcePosition
import org.alephium.ralph.error.CompilerError.FormattableError

/** Builds a formatted error message.
  *
  * @param errorTitle
  *   Error header. This can be error-types: `Syntax error`, `Type mismatch error` etc.
  * @param errorLine
  *   Line where this error occurred.
  * @param foundLength
  *   The length of string token(s) that lead to this failure.
  * @param errorMessage
  *   Error message to display under the pointer.
  * @param errorFooter
  *   Optionally add more error details/hints/suggestions to the footer.
  * @param sourcePosition
  *   Location of where this error occurred.
  */

final case class CompilerErrorFormatter(
    errorTitle: String,
    errorLine: String,
    foundLength: Int,
    errorMessage: String,
    errorFooter: Option[String],
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
  // scalastyle:off method.length
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def format(errorColor: Option[String] = None): String = {
    val lineNumGutter   = s"${sourcePosition.rowNum} |"
    val lineNumGutterHL = highlight(lineNumGutter, errorColor)

    val emptyLineNumGutterPaddingLeft = " " * (lineNumGutter.length - 1)
    val emptyLineNumGutter            = highlight(emptyLineNumGutterPaddingLeft + "|", errorColor)

    val errorTag    = highlight(s"-- error ${sourcePosition.format}: ", errorColor)
    val errorHeader = highlight(errorTitle, errorColor)

    val paddingLeft = " " * sourcePosition.colIndex
    val pointerMarker = {
      // the current CompilerErrorFormatter is not aware of multi-line errors and
      // can produce some weird results.
      // This is a quick fix to prevent the pointer from going off the screen.
      val fixMultiLineLength = errorLine.length - sourcePosition.colIndex
      val length = if (fixMultiLineLength > 0) {
        scala.math.min(foundLength, fixMultiLineLength)
      } else {
        foundLength
      }
      CompilerErrorFormatter.pointer * length
    }

    // Add padding & expected only if there is a message to append at the end.
    val paddingLeftExpected =
      if (errorMessage.isEmpty) {
        ""
      } else {
        s"$paddingLeft$errorMessage"
      }

    // Build error body
    val errorBody =
      s"""$errorTag$errorHeader
         |$lineNumGutterHL$errorLine
         |$emptyLineNumGutter$paddingLeft$pointerMarker
         |$emptyLineNumGutter$paddingLeftExpected"""

    val errorBodyStripped =
      errorBody.stripMargin

    // Build error footer
    errorFooter match {
      case Some(footer) =>
        val emptyLineNumberGutterLength =
          emptyLineNumGutter.length

        val marginLength = // max length of the footer margin.
          errorBodyStripped.linesIterator.foldLeft(footer.length) { case (currentMax, nextLine) =>
            currentMax max (nextLine.length - emptyLineNumberGutterLength)
          }

        val footerMargin = highlight("-" * marginLength, errorColor)

        s"""$errorBody
           |$emptyLineNumGutter$footerMargin
           |$emptyLineNumGutter$footer
           |""".stripMargin

      case None =>
        errorBodyStripped + System.lineSeparator()
    }
  }
  // scalastyle:on method.length

}

object CompilerErrorFormatter {

  val pointer = "^"

  def apply(error: FormattableError, program: String): CompilerErrorFormatter = {
    val fastParseLineNumber = IndexedParserInput(program).prettyIndex(error.position)
    val sourcePosition      = SourcePosition.parse(fastParseLineNumber)
    val errorLine           = getErroredLine(sourcePosition.rowIndex, program)

    CompilerErrorFormatter(
      errorTitle = error.title,
      errorLine = errorLine,
      foundLength = error.foundLength,
      errorMessage = error.message,
      errorFooter = error.footer,
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

  /** Wraps the input String to be coloured */
  private def highlight(msg: String, color: Option[String]): String =
    color match {
      case Some(color) =>
        color + msg + Console.RESET

      case None =>
        msg
    }
}
