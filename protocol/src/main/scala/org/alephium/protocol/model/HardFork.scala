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

// Checklist for introducing a new upgrade:
// 1. Activation timestamp in network config files.
// 2. `checkUpgrade` function in `ReleaseVersion.scala`.
// 3. Enable `checkInactiveInstructions` in `Instr.scala`.
// 4. `sanityCheck` function in `AlephiumConfig.scala`.
//
// Checklist for setting up upgrade activation:
// 1. Update activation timestamps in config files.
// 2. `sanityCheck` function in `AlephiumConfig.scala`.
// 3. Update checks in `ReleaseVersion.scala`.
sealed class HardFork(val version: Int) extends Ordered[HardFork] {
  def compare(that: HardFork): Int = this.version.compareTo(that.version)

  def isLemanEnabled(): Boolean  = this >= HardFork.Leman
  def isRhoneEnabled(): Boolean  = this >= HardFork.Rhone
  def isDanubeEnabled(): Boolean = this >= HardFork.Danube
}
object HardFork {
  object Mainnet extends HardFork(0)
  object Leman   extends HardFork(1)
  object Rhone   extends HardFork(2)
  object Danube  extends HardFork(3)

  val All: ArraySeq[HardFork] = ArraySeq(Mainnet, Leman, Rhone, Danube)

  // TestOnly
  def SinceLemanForTest: HardFork = All.drop(1).apply(Random.nextInt(All.length - 1))
  def PreRhoneForTest: HardFork   = All.take(2).apply(Random.nextInt(2))
}
