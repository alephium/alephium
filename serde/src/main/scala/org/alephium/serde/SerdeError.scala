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

package org.alephium.serde

sealed abstract class SerdeError(message: String) extends Exception(message)

object SerdeError {
  final case class NotEnoughBytes(message: String) extends SerdeError(message)
  final case class WrongFormat(message: String)    extends SerdeError(message)
  final case class Validation(message: String)     extends SerdeError(message)
  final case class Other(message: String)          extends SerdeError(message)

  def notEnoughBytes(expected: Int, got: Int): NotEnoughBytes =
    NotEnoughBytes(s"Too few bytes: expected $expected, got $got")

  def redundant(expected: Int, got: Int): WrongFormat =
    WrongFormat(s"Too many bytes: expected $expected, got $got")

  def validation(message: String): Validation = Validation(message)

  def wrongFormat(message: String): WrongFormat = WrongFormat(message)

  def other(message: String): Other = Other(message)
}
