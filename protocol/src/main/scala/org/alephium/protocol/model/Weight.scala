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

import java.math.BigInteger

import org.alephium.serde.Serde

final case class Weight(value: BigInteger) extends AnyVal with Ordered[Weight] {
  def +(that: Weight): Weight = Weight(this.value.add(that.value))

  def *(n: Int): Weight = Weight(value.multiply(BigInteger.valueOf(n.toLong)))

  override def compare(that: Weight): Int = this.value.compareTo(that.value)
}

object Weight {
  implicit val serde: Serde[Weight] = Serde.forProduct1(Weight(_), _.value)

  val zero: Weight = Weight(BigInteger.ZERO)

  def from(target: Target): Weight = Weight(Target.maxBigInt.divide(target.value))
}
