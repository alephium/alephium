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

object FastParseErrorUtil {

  /** Builds a Ralph compiler error type [[CompilerError.FastParseError]] from FastParser's
    * `Parsed.Failure` result.
    *
    * @param traced
    *   FastParser's failure run result.
    * @return
    *   Ralph error type.
    */

  def apply(traced: Parsed.TracedFailure): CompilerError.FastParseError = {
    val program =
      traced.input.slice(0, traced.input.length)

    val erroredIndex =
      getErroredIndex(traced)

    val expected =
      getLatestErrorMessage(traced, erroredIndex)

    val foundQuoted =
      Parsed.Failure.formatTrailing(traced.input, erroredIndex)

    val foundNoQuotes =
      dropQuotes(foundQuoted)

    val expectedMessage =
      "Expected " + expected

    val traceMsg = // report trace in the error footer
      "Trace log: " + traced.longMsg

    CompilerError.FastParseError(
      position = erroredIndex,
      message = expectedMessage,
      found = foundNoQuotes,
      tracedMsg = traceMsg,
      program = program
    )
  }

  /** Removes wrapper quotes from the output by `Parsed.Failure.formatTrailing`. */
  def dropQuotes(string: String): String =
    string.replaceFirst("""^"(.+)"$""", "$1")

  /** Use index with maximum value i.e. the latest errored code */
  private def getErroredIndex(traced: Parsed.TracedFailure): Int =
    traced.stack.foldLeft(traced.index)(_ max _._2)

  /** Fetch the most recent error message. */
  def getLatestErrorMessage(traced: Parsed.TracedFailure, forIndex: Int): String = {
    // label is added to the tail-end because in `Parsed.TracedFailure.longMsg` label gets preference.
    val stack = traced.stack.appended((traced.label, traced.index))

    stack
      .filter(_._2 == forIndex) // all parsers for this index
      .lastOption
      .map(_._1)
      .getOrElse(traced.label) // if index not found, use label
  }

}
