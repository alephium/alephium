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

final case class SourceIndex(index: Int, width: Int, fileURI: Option[java.net.URI]) {
  val endIndex: Int                       = index + width
  def addToWidth(width: Int): SourceIndex = copy(width = this.width + width)
}

object SourceIndex {
  def apply(from: Option[SourceIndex], to: Option[SourceIndex]): Option[SourceIndex] =
    for {
      f <- from
      t <- to
    } yield SourceIndex(f.index, t.endIndex - f.index, f.fileURI)

  def empty: SourceIndex = SourceIndex(0, 0, None)
}
