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

package org.alephium.util

object OptionF {
  // scalastyle:off return
  def fold[E, R](elems: IterableOnce[E], zero: R)(
      op: (R, E) => Option[R]
  ): Option[R] = {
    var result   = zero
    val iterator = elems.iterator
    while (iterator.hasNext) {
      op(result, iterator.next()) match {
        case None    => return None
        case Some(r) => result = r
      }
    }
    Some(result)
  }
  // scalastyle:on return
}
