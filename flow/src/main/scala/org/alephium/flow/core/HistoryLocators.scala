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
    if (toHeight == fromHeight) {
      AVector(fromHeight)
    } else if (toHeight == fromHeight + 1) {
      AVector(fromHeight, toHeight)
    } else {
      val middleHeight = fromHeight + (toHeight - fromHeight) / 2
      val heights      = mutable.ArrayBuffer(fromHeight)

      var shift  = 1
      var height = fromHeight + shift // may overflow
      while (height >= 0 && height <= middleHeight) {
        heights.addOne(height)
        shift *= 2
        height = fromHeight + shift
      }

      height = toHeight - shift
      while (height <= middleHeight) {
        shift /= 2
        height = toHeight - shift
      }

      while (shift >= 1) {
        heights.addOne(height)
        shift /= 2
        height = toHeight - shift
      }

      heights.addOne(toHeight)
      AVector.from(heights)
    }
  }
}
