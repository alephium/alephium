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

package org.alephium.flow.core

import scala.collection.mutable

import org.alephium.util.AVector

object HistoryLocators {
  // fromHeight and toHeight are inclusive
  def sampleHeights(fromHeight: Int, toHeight: Int): AVector[Int] = {
    assume(fromHeight <= toHeight && fromHeight >= 0)
    val heights = mutable.ArrayBuffer(toHeight)
    var shift   = 1
    var height  = toHeight - shift
    while (height >= fromHeight) {
      heights += height
      shift *= 2
      height = toHeight - shift
    }
    AVector.from(heights).reverse
  }
}
