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

import java.math.BigInteger

object Number {
  def isPositive(n: BigInteger): Boolean  = n.signum() > 0
  def nonNegative(n: BigInteger): Boolean = n.signum() >= 0
  def isNegative(n: BigInteger): Boolean  = n.signum() < 0
  def nonPositive(n: BigInteger): Boolean = n.signum() <= 0

  // scalastyle:off magic.number
  val million: Long     = 1000000L
  val billion: Long     = 1000000000L
  val trillion: Long    = 1000000000000L
  val quadrillion: Long = 1000000000000000L
  val quintillion: Long = 1000000000000000000L
  // scalastyle:on magic.number
}
