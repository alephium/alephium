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

package org.alephium.protocol.model

import scala.collection.immutable.ArraySeq
import scala.util.Random

sealed class HardFork(val version: Int) extends Ordered[HardFork] {
  def compare(that: HardFork): Int = this.version.compareTo(that.version)

  def isLemanEnabled(): Boolean = this >= HardFork.Leman
  def isRhoneEnabled(): Boolean = this >= HardFork.Rhone
}
object HardFork {
  object Mainnet extends HardFork(0)
  object Leman   extends HardFork(1)
  object Rhone   extends HardFork(2)

  val All: ArraySeq[HardFork] = ArraySeq(Mainnet, Leman, Rhone)

  // TestOnly
  def SinceLemanForTest: HardFork = All.drop(1).apply(Random.nextInt(2))
  def PreRhoneForTest: HardFork   = All.take(2).apply(Random.nextInt(2))
}
