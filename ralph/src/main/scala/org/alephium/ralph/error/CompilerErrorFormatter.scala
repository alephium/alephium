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

import org.alephium.ralph.SourcePosition

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
