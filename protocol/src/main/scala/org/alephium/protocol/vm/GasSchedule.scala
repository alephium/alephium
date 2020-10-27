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

package org.alephium.protocol.vm

trait GasSchedule {
  protected def typeHint(): Unit // for type safety
}

trait GasSimple extends GasSchedule {
  protected def typeHint(): Unit = ()

  def gas: Int
}

trait GasFormula extends GasSchedule {
  protected def typeHint(): Unit = ()

  def gas(size: Int): Int
}

trait GasZero extends GasSimple {
  val gas: Int = 0
}

trait GasVeryLow extends GasSimple {
  val gas: Int = 3
}

trait GasLow extends GasSimple {
  val gas: Int = 5
}

trait GasMid extends GasSimple {
  val gas: Int = 8
}

trait GasHigh extends GasSimple {
  val gas: Int = 10
}

trait GasHash extends GasFormula {
  def gas(inputLength: Int): Int = (30 + 6 * ((inputLength + 3) / 8))
}

trait GasSignature extends GasSimple {
  val gas: Int = 500 // TODO: bench this
}

trait GasCreate extends GasSimple {
  val gas: Int = 32000
}

trait GasBalance extends GasSimple {
  val gas: Int = 50
}

trait GasCall extends GasFormula {
  def gas(size: Int): Int = ??? // should call the companion object instead
}

object GasSchedule {
  val callGas: Int = 200
}
