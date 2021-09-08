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

package org.alephium.api.model

import org.alephium.protocol.ALF
import org.alephium.util.U256

final case class Amount(value: U256) {
  override def toString: String = value.toString
}

object Amount {
  // x.x ALPH format
  def from(string: String): Option[Amount] = {
    val regex = """([0-9]*\.?[0-9]+) *ALPH""".r
    string match {
      case regex(v) =>
        val bigDecimal = new java.math.BigDecimal(v)
        val scaling    = java.math.BigDecimal.valueOf(10).pow(bigDecimal.scale)
        U256
          .from(bigDecimal.multiply(scaling).toBigInteger)
          .map(_.mulUnsafe(ALF.oneAlf).divUnsafe(U256.unsafe(scaling.toBigInteger)))
          .map(Amount(_))
      case _ => None
    }
  }

  val Zero: Amount = Amount(U256.Zero)
}
