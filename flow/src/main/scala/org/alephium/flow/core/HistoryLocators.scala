package org.alephium.flow.core

import scala.collection.mutable

import org.alephium.protocol.Hash
import org.alephium.protocol.message.HashLocators
import org.alephium.util.AVector

final case class HistoryLocators private (hashes: AVector[ChainLocators]) {
  def toHashLocators: HashLocators = HashLocators(hashes.map(_.hashes))
}

object HistoryLocators {
  def unsafe(hashes: AVector[ChainLocators]): HistoryLocators = {
    new HistoryLocators(hashes)
  }

  def from(hashLocators: HashLocators): HistoryLocators = {
    new HistoryLocators(hashLocators.hashes.map(ChainLocators.unsafe))
  }

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

final case class ChainLocators private (hashes: AVector[Hash]) {
  def isFixedPoint: Boolean = hashes.length == 1

  def fixedPointUnsafe: Hash = {
    assume(isFixedPoint)
    hashes.head
  }

  def fixedPoint: Option[Hash] = Option.when(isFixedPoint)(hashes.head)

  def isForkLocated(index: Int): Boolean = {
    assume(index >= 1 && index < hashes.length)
    val midIndex = hashes.length / 2
    if (index <= midIndex) index <= 6
    else hashes.length - 1 - index <= 6
  }
}

object ChainLocators {
  def fixedPoint(hash: Hash): ChainLocators        = new ChainLocators(AVector(hash))
  def unsafe(hashes: AVector[Hash]): ChainLocators = new ChainLocators(hashes)
}

sealed trait HistoryComparisonResult

object HistoryComparisonResult {
  final case class Common(tips: AVector[Hash])                 extends HistoryComparisonResult
  final case class Unsure(newHistoryLocators: HistoryLocators) extends HistoryComparisonResult
}
