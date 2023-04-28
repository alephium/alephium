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

/** @param rowNum
  *   Line in the source/program. Following FastParse, this starts at `1`.
  * @param colNum
  *   Column in the source/program. Following FastParse, this starts at `1`.
  */
final case class SourcePosition(rowNum: Int, colNum: Int) {
  def rowIndex: Int =
    rowNum - 1

  def colIndex: Int =
    colNum - 1

  def format: String =
    s"($rowNum:$colNum)"
}

object SourcePosition {

  /** Parse line number into `SourcePosition`.
    *
    * @param lineNum
    *   a String of format `int:int`
    * @return
    *   A `SourcePosition` or throws if the input format is valid.
    */
  def parse(lineNum: String): SourcePosition = {
    val lineNumAndIndex = lineNum.split(":").filter(_.nonEmpty)

    if (lineNumAndIndex.length == 2) {
      try
        SourcePosition(
          rowNum = lineNumAndIndex.head.toInt,
          colNum = lineNumAndIndex.last.toInt
        )
      catch {
        case throwable: Throwable =>
          throw Compiler.Error(unsupportedLineNumberFormat(lineNum), throwable)
      }
    } else {
      // TODO: is there a preferred way of handling error like these other than
      //       throwing exception?
      // There is no usage of other line number formats supported by FastParse.
      // So this is reported as unsupported.
      throw Compiler.Error(unsupportedLineNumberFormat(lineNum))
    }
  }

  def unsupportedLineNumberFormat(string: String): String =
    s"Unsupported line number format: $string"

}
