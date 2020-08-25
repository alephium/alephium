package org.alephium.flow.core

import scala.collection.mutable

import org.alephium.util.AVector

object HistoryLocators {
  // fromHeight and toHeight are inclusive
  def sampleHeights(fromHeight: Int, toHeight: Int): AVector[Int] = {
    assume(fromHeight <= toHeight && fromHeight >= 0)
    if (toHeight == fromHeight) AVector(fromHeight)
    else if (toHeight == fromHeight + 1) AVector(fromHeight, toHeight)
    else {
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
