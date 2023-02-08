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

import org.alephium.protocol.model.{maximalGasPerTx, minimalGas}
import org.alephium.serde.Serde
import org.alephium.util.U256

final case class GasBox private (value: Int) extends AnyVal with Ordered[GasBox] {
  def use(amount: GasBox): ExeResult[GasBox] = {
    if (this >= amount) {
      Right(GasBox(value - amount.value))
    } else {
      failed(OutOfGas)
    }
  }

  def mulUnsafe(n: Int): GasBox = {
    assume(n >= 0)
    GasBox.unsafe(value * n)
  }

  def addUnsafe(another: GasBox): GasBox = {
    GasBox.unsafe(value + another.value)
  }

  def sub(another: GasBox): Option[GasBox] = {
    if (this >= another) {
      Some(GasBox.unsafe(value - another.value))
    } else {
      None
    }
  }

  def subUnsafe(another: GasBox): GasBox = {
    assume(this >= another)
    GasBox.unsafe(value - another.value)
  }

  def toU256: U256 = U256.unsafe(value)

  override def compare(that: GasBox): Int = this.value.compare(that.value)
}

object GasBox {
  implicit val serde: Serde[GasBox] = Serde
    .forProduct1[Int, GasBox](new GasBox(_), _.value)
    .validate(box => if (box.value >= 0) Right(()) else Left(s"Negative gas ${box.value}"))

  val zero: GasBox = GasBox.unsafe(0)

  def unsafe(initialGas: Int): GasBox = {
    assume(initialGas >= 0)
    new GasBox(initialGas)
  }

  def from(gas: Int): Option[GasBox] = Option.when(gas >= 0)(new GasBox(gas))

  def from(gasFee: U256, gasPrice: U256): Option[GasBox] = {
    for {
      rawGas <- gasFee.div(gasPrice)
      result <- from(rawGas.toBigInt.intValue())
    } yield result
  }

  def unsafeTest(gas: Int): GasBox = new GasBox(gas)

  @inline def validate(box: GasBox): Boolean = {
    box >= minimalGas && box <= maximalGasPerTx
  }
}
