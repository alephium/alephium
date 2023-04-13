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

import fastparse.Parsed

import org.alephium.ralph.SourcePosition

object FastParseErrorUtil {

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
    FastParseErrorUtil(failure.trace())

  def apply(traced: Parsed.TracedFailure): CompilerErrorFormatter = {
    val program =
      traced.input.slice(0, traced.input.length)

    val erroredIndex =
      getErroredIndex(traced)

    val sourcePosition =
      SourcePosition.parse(traced.input.prettyIndex(erroredIndex))

    val errorLine =
      CompilerErrorFormatter.getErroredLine(sourcePosition.rowIndex, program)

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

  /** Removes wrapper quotes from the output by `Parsed.Failure.formatTrailing`. */
  def dropQuotes(string: String): String =
    string.replaceFirst("""^"(.+)"$""", "$1")

  /** Use index with maximum value i.e. the latest errored code */
  private def getErroredIndex(traced: Parsed.TracedFailure): Int =
    traced.stack.foldLeft(traced.index)(_ max _._2)

  /** Fetch the most recent error message. */
  private def getLatestErrorMessage(traced: Parsed.TracedFailure, forIndex: Int): String =
    traced.stack
      .filter(_._2 == forIndex) // all parsers for this index
      .lastOption
      .map(_._1)               // use the last errored
      .getOrElse(traced.label) // if none found, use the label

}
