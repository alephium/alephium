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

import org.alephium.protocol.ALPH
import org.alephium.protocol.model.minimalGasPrice
import org.alephium.serde.Serde
import org.alephium.util.U256

final case class GasPrice(value: U256) extends Ordered[GasPrice] {
  // this is safe as value <= ALPH.MaxALPHValue
  def *(gas: GasBox): U256 = {
    value.mulUnsafe(gas.toU256)
  }

  override def compare(that: GasPrice): Int = this.value.compare(that.value)
}

object GasPrice {
  implicit val serde: Serde[GasPrice] = Serde.forProduct1(GasPrice.apply, _.value)

  def validate(gasPrice: GasPrice): Boolean = {
    gasPrice >= minimalGasPrice && gasPrice.value < ALPH.MaxALPHValue
  }
}
